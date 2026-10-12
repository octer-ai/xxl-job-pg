-- Run after importing explicit IDs into PostgreSQL, with application writes stopped.
-- Connect to the target database/schema first.
DO $$
DECLARE
    table_name TEXT;
    max_id BIGINT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'xxl_job_group', 'xxl_job_registry', 'xxl_job_info', 'xxl_job_logglue',
        'xxl_job_log', 'xxl_job_log_report', 'xxl_job_user'
    ] LOOP
        EXECUTE format('SELECT MAX(id) FROM %I', table_name) INTO max_id;
        PERFORM setval(pg_get_serial_sequence(table_name, 'id'),
                       COALESCE(max_id, 1), max_id IS NOT NULL);
    END LOOP;
END;
$$;
