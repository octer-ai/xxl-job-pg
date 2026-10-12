package com.xxl.job.admin.business.mapper;

import com.xxl.job.admin.business.model.*;
import com.xxl.job.admin.framework.mapper.XxlJobUserMapper;
import com.xxl.job.admin.framework.model.XxlJobUser;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real PostgreSQL schema and mapper XML without starting scheduler threads. */
@EnabledIfEnvironmentVariable(named = "XXL_JOB_TEST_PG_URL", matches = "jdbc:postgresql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgreSqlMapperTest {
    private final String schema = "xxl_job_test_" + UUID.randomUUID().toString().replace("-", "");
    private SqlSessionFactory factory;
    private String url;
    private Properties credentials;

    @BeforeAll
    void initializeDatabase() throws Exception {
        url = System.getenv("XXL_JOB_TEST_PG_URL");
        credentials = new Properties();
        credentials.setProperty("user", System.getenv().getOrDefault("XXL_JOB_TEST_PG_USER", "postgres"));
        credentials.setProperty("password", System.getenv().getOrDefault("XXL_JOB_TEST_PG_PASSWORD", "postgres_pwd"));
        try (Connection connection = DriverManager.getConnection(url, credentials);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            Path script = Path.of("../doc/db/tables_xxl_job.sql");
            if (!Files.exists(script)) {
                script = Path.of("doc/db/tables_xxl_job.sql");
            }
            statement.execute(Files.readString(script));
        }
        Properties properties = new Properties();
        properties.putAll(credentials);
        properties.setProperty("currentSchema", schema);
        UnpooledDataSource dataSource = new UnpooledDataSource("org.postgresql.Driver", url, properties);
        Configuration configuration = new Configuration(new Environment("postgresql-test",
                new JdbcTransactionFactory(), dataSource));
        for (String mapper : List.of("business/XxlJobGroupMapper", "business/XxlJobInfoMapper",
                "business/XxlJobLogMapper", "business/XxlJobLogGlueMapper", "business/XxlJobLogReportMapper",
                "business/XxlJobRegistryMapper", "business/XxlJobLockMapper", "framework/XxlJobUserMapper")) {
            String resource = "mapper/" + mapper + ".xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    void dropTestSchema() throws Exception {
        if (url != null) {
            try (Connection connection = DriverManager.getConnection(url, credentials);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void generatedIdsPaginationAndScheduling() {
        try (SqlSession session = factory.openSession()) {
            XxlJobGroupMapper groups = session.getMapper(XxlJobGroupMapper.class);
            XxlJobGroup group = new XxlJobGroup();
            group.setAppname("postgres-test");
            group.setName("测试执行器 🐘");
            assertEquals(1, groups.save(group));
            assertTrue(group.getId() > 2, "Identity must advance past the seed IDs");
            assertEquals(group.getName(), groups.load(group.getId()).getName());
            assertEquals(1, groups.pageList(0, 1, "postgres-test", "测试").size());
            assertTrue(groups.pageList(1, 1, "postgres-test", null).isEmpty());
            group.setName("updated");
            assertEquals(1, groups.update(group));

            XxlJobInfoMapper jobs = session.getMapper(XxlJobInfoMapper.class);
            XxlJobInfo job = jobs.loadById(1);
            job.setId(0);
            job.setJobGroup(group.getId());
            job.setName("postgres-test-job");
            job.setTriggerStatus(1);
            assertEquals(1, jobs.save(job));
            assertTrue(job.getId() > 4);
            assertEquals(job.getId(), jobs.pageList(0, 1, group.getId(), 1,
                    "postgres-test", "demo", "XXL").get(0).getId());
            assertTrue(jobs.pageList(1, 1, group.getId(), 1, null, null, null).isEmpty());
            job.setTriggerLastTime(100);
            job.setTriggerNextTime(200);
            assertEquals(1, jobs.scheduleBatchUpdate(List.of(job)));
            assertEquals(200, jobs.loadById(job.getId()).getTriggerNextTime());
            assertTrue(jobs.scheduleJobQuery(200, 10).stream().anyMatch(item -> item.getId() == job.getId()));
            job.setTriggerStatus(0);
            assertEquals(1, jobs.scheduleUpdate(job));
            assertEquals(0, jobs.scheduleUpdate(job));
            assertEquals(1, jobs.delete(job.getId()));

            XxlJobUserMapper users = session.getMapper(XxlJobUserMapper.class);
            XxlJobUser user = new XxlJobUser();
            user.setUsername("postgres-test");
            user.setPassword("test-password");
            assertEquals(1, users.save(user));
            assertTrue(user.getId() > 1);
            assertEquals(user.getId(), users.pageList(0, 1, "postgres-test", 0).get(0).getId());
            assertTrue(users.pageList(1, 1, "postgres-test", 0).isEmpty());
            assertEquals(1, users.delete(user.getId()));
            assertEquals(1, groups.remove(group.getId()));
        }
    }

    @Test
    void registryUpsertAndExpiry() {
        try (SqlSession session = factory.openSession()) {
            XxlJobRegistryMapper registry = session.getMapper(XxlJobRegistryMapper.class);
            Date now = new Date();
            Date expired = new Date(now.getTime() - 60_000);
            assertEquals(1, registry.registrySaveOrUpdate("EXECUTOR", "test", "address", expired));
            List<Long> dead = registry.findDead(30, now);
            assertEquals(1, dead.size());
            assertTrue(registry.findAll(30, now).isEmpty());
            assertEquals(1, registry.registrySaveOrUpdate("EXECUTOR", "test", "address", now));
            assertTrue(registry.findDead(30, now).isEmpty());
            assertEquals(1, registry.findAll(30, now).size());
            assertEquals(dead.get(0).longValue(), registry.findAll(30, now).get(0).getId());
            assertEquals(1, registry.removeDead(dead));
        }
    }

    @Test
    void registryExpirySupportsBigintIds() throws Exception {
        try (SqlSession session = factory.openSession();
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("ALTER TABLE xxl_job_registry ALTER COLUMN id RESTART WITH 2147483648");
            XxlJobRegistryMapper registry = session.getMapper(XxlJobRegistryMapper.class);
            Date now = new Date();
            assertEquals(1, registry.registrySaveOrUpdate("EXECUTOR", "bigint-test", "address",
                    new Date(now.getTime() - 60_000)));
            List<Long> expired = registry.findDead(30, now);
            assertEquals(List.of(2147483648L), expired);
            assertEquals(1, registry.removeDead(expired));
            assertTrue(registry.findDead(30, now).isEmpty());
        }
    }

    @Test
    void accountAndAppnameMatchingRemainCaseInsensitive() throws Exception {
        try (SqlSession session = factory.openSession()) {
            XxlJobUserMapper users = session.getMapper(XxlJobUserMapper.class);
            assertEquals(1, users.loadByUserName("ADMIN").getId());
            assertEquals(1, users.pageList(0, 10, "ADMIN", -1).size());
            assertEquals(1, users.pageListCount(0, 10, "ADMIN", -1));
            XxlJobGroupMapper groups = session.getMapper(XxlJobGroupMapper.class);
            assertEquals(1, groups.loadByAppname("XXL-JOB-EXECUTOR-SAMPLE").getId());
            assertEquals(2, groups.pageList(0, 10, "XXL-JOB-EXECUTOR-SAMPLE", null).size());
            assertEquals(2, groups.pageListCount(0, 10, "XXL-JOB-EXECUTOR-SAMPLE", null));
            XxlJobInfoMapper jobs = session.getMapper(XxlJobInfoMapper.class);
            assertEquals(1, jobs.pageList(0, 10, 1, -1, null, "DEMOJOBHANDLER", "xxl").size());
            assertEquals(1, jobs.pageListCount(0, 10, 1, -1, null, "DEMOJOBHANDLER", "xxl"));
        }
        for (String sql : List.of(
                "INSERT INTO xxl_job_user(username, password, role) VALUES ('ADMIN', 'test', 0)",
                "INSERT INTO xxl_job_group(app_name, name) VALUES ('XXL-JOB-EXECUTOR-SAMPLE', 'test')")) {
            try (SqlSession session = factory.openSession();
                 Statement statement = session.getConnection().createStatement()) {
                SQLException error = assertThrows(SQLException.class, () -> statement.execute(sql));
                assertEquals("23505", error.getSQLState(), "Case variants must violate uniqueness");
                session.rollback(true);
            }
        }
    }

    @Test
    void logStatisticsAndRetention() {
        try (SqlSession session = factory.openSession()) {
            XxlJobLogMapper logs = session.getMapper(XxlJobLogMapper.class);
            Date now = new Date();
            Date from = new Date(now.getTime() - 10_000);
            Date to = new Date(now.getTime() + 10_000);
            assertEquals(0L, ((Number) logs.findLogReport(from, to).get("triggerDayCountRunning")).longValue());
            XxlJobLog running = new XxlJobLog();
            running.setJobGroup(1);
            running.setJobId(1);
            running.setTriggerTime(from);
            running.setTriggerCode(200);
            assertEquals(1L, logs.save(running));
            assertTrue(running.getId() > 0);
            XxlJobLog success = new XxlJobLog();
            success.setJobGroup(1);
            success.setJobId(1);
            success.setTriggerTime(now);
            success.setHandleCode(200);
            assertEquals(1L, logs.save(success));
            Map<String, Object> report = logs.findLogReport(from, to);
            assertEquals(2L, ((Number) report.get("triggerDayCount")).longValue());
            assertEquals(1L, ((Number) report.get("triggerDayCountRunning")).longValue());
            assertEquals(1L, ((Number) report.get("triggerDayCountSuc")).longValue());
            assertEquals(running.getId(), logs.pageList(1, 1, 1, 1, from, to, 0).get(0).getId());
            assertEquals(List.of(running.getId()), logs.findClearLogIds(1, 1, to, 1, 10));
            XxlJobLog untimed = new XxlJobLog();
            untimed.setJobGroup(1);
            untimed.setJobId(1);
            assertEquals(1L, logs.save(untimed));
            assertEquals(List.of(running.getId(), untimed.getId()),
                    logs.findClearLogIds(1, 1, null, 1, 10),
                    "A NULL timestamp must not displace the newest timed log");
            assertEquals(1, logs.clearLog(List.of(running.getId())));
            assertNull(logs.load(running.getId()));
        }
    }

    @Test
    void reportUpsertAndGlueRetention() {
        try (SqlSession session = factory.openSession()) {
            XxlJobLogReportMapper reports = session.getMapper(XxlJobLogReportMapper.class);
            XxlJobLogReport report = new XxlJobLogReport();
            Date now = new Date();
            report.setTriggerDay(now);
            report.setUpdateTime(now);
            report.setRunningCount(1);
            assertEquals(1, reports.saveOrUpdate(report));
            int id = report.getId();
            assertTrue(id > 0);
            report.setId(0);
            report.setRunningCount(2);
            report.setSucCount(3);
            report.setFailCount(4);
            assertEquals(1, reports.saveOrUpdate(report));
            assertEquals(id, report.getId(), "Upsert must return the existing ID");
            List<XxlJobLogReport> result = reports.queryLogReport(new Date(now.getTime() - 1000),
                    new Date(now.getTime() + 1000));
            assertEquals(1, result.size());
            assertEquals(2, result.get(0).getRunningCount());
            assertEquals(3, reports.queryLogReportTotal().getSucCount());
            assertEquals(4, reports.queryLogReportTotal().getFailCount());

            XxlJobLogGlueMapper glue = session.getMapper(XxlJobLogGlueMapper.class);
            XxlJobLogGlue old = new XxlJobLogGlue();
            old.setJobId(1);
            old.setGlueRemark("old");
            old.setUpdateTime(new Date(now.getTime() - 1000));
            assertEquals(1, glue.save(old));
            assertTrue(old.getId() > 0);
            XxlJobLogGlue latest = new XxlJobLogGlue();
            latest.setJobId(1);
            latest.setGlueRemark("latest");
            latest.setUpdateTime(now);
            assertEquals(1, glue.save(latest));
            XxlJobLogGlue untimed = new XxlJobLogGlue();
            untimed.setJobId(1);
            untimed.setGlueRemark("untimed");
            assertEquals(1, glue.save(untimed));
            assertEquals(2, glue.removeOld(1, 1));
            assertEquals(latest.getId(), glue.findByJobId(1).get(0).getId());
        }
    }

    @Test
    void timestampRoundTripAndNullFields() throws Exception {
        try (SqlSession session = factory.openSession();
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("SET TIME ZONE 'Asia/Shanghai'");
            XxlJobLogMapper logs = session.getMapper(XxlJobLogMapper.class);
            XxlJobLog log = new XxlJobLog();
            log.setJobGroup(1);
            log.setJobId(1);
            Date time = new Date(1767225600123L);
            log.setTriggerTime(time);
            assertEquals(1L, logs.save(log));
            assertEquals(time, logs.load(log.getId()).getTriggerTime());
            log.setExecutorParam("中文参数 🐘");
            log.setExecutorHandler("");
            log.setTriggerMsg("triggered");
            assertEquals(1, logs.updateTriggerInfo(log));
            assertEquals("中文参数 🐘", logs.load(log.getId()).getExecutorParam());
            log.setHandleCode(200);
            log.setHandleTime(time);
            assertEquals(1, logs.updateHandleInfo(log));
            assertEquals(time, logs.load(log.getId()).getHandleTime());
            assertEquals(1, logs.load(log.getId()).getAlarmStatus());
            // PostgreSQL must preserve NULL and empty strings as different values.
            try (PreparedStatement query = session.getConnection().prepareStatement(
                    "SELECT executor_address IS NULL, executor_handler = '' FROM xxl_job_log WHERE id = ?")) {
                query.setLong(1, log.getId());
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertTrue(result.getBoolean(1));
                    assertTrue(result.getBoolean(2));
                }
            }
        }
    }

    @Test
    void importedIdsAndEmptyTablesHaveCorrectSequences() throws Exception {
        try (SqlSession session = factory.openSession()) {
            try (Statement statement = session.getConnection().createStatement()) {
                statement.execute("INSERT INTO xxl_job_log(id, job_group, job_id, trigger_code, handle_code) "
                        + "VALUES (2147483648, 1, 1, 0, 0)");
                Path script = Path.of("../doc/db/reset_identity_sequences.sql");
                if (!Files.exists(script)) {
                    script = Path.of("doc/db/reset_identity_sequences.sql");
                }
                statement.execute(Files.readString(script));
                Map<String, Long> expected = Map.of("xxl_job_group", 3L, "xxl_job_registry", 1L,
                        "xxl_job_info", 5L, "xxl_job_logglue", 1L, "xxl_job_log", 2147483649L,
                        "xxl_job_log_report", 1L, "xxl_job_user", 2L);
                for (var entry : expected.entrySet()) {
                    try (var result = statement.executeQuery("SELECT nextval(pg_get_serial_sequence('"
                            + entry.getKey() + "', 'id'))")) {
                        assertTrue(result.next());
                        assertEquals(entry.getValue().longValue(), result.getLong(1), entry.getKey());
                    }
                }
            } finally {
                // Direct JDBC writes do not mark a MyBatis session as dirty.
                session.rollback(true);
            }
        }
    }

    @Test
    void scheduleLockExcludesConcurrentTransactions() throws Exception {
        try (SqlSession first = factory.openSession(); SqlSession second = factory.openSession()) {
            assertEquals("schedule_lock", first.getMapper(XxlJobLockMapper.class).scheduleLock());
            try (Statement statement = second.getConnection().createStatement()) {
                SQLException error = assertThrows(SQLException.class, () -> statement.execute(
                        "SELECT lock_name FROM xxl_job_lock WHERE lock_name = 'schedule_lock' FOR UPDATE NOWAIT"));
                assertEquals("55P03", error.getSQLState());
            }
            second.rollback(true);
            first.rollback(true);
            assertEquals("schedule_lock", second.getMapper(XxlJobLockMapper.class).scheduleLock());
        }
    }
}
