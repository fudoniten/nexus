CREATE OR REPLACE FUNCTION update_zone_serial_on_change()
RETURNS TRIGGER AS $$
DECLARE
  zone_id VARCHAR;
BEGIN
  -- Get the affected zone name from the changed row
  IF TG_OP = 'DELETE' THEN
     zone_id := OLD.domain_id;
  ELSE
     zone_id := NEW.domain_id;
  END IF;

  UPDATE domains
  SET serial = serial + 1
  WHERE id = zone_name;

  IF TG_OP = 'DELETE' THEN
     RETURN OLD;
  ELSE
     RETURN NEW;
  END IF;
END;
$$LANGUAGE plpgsql;

CREATE TRIGGER update_serial_on_change_trigger
AFTER INSERT OR UPDATE OR DELETE ON records
FOR EACH ROW EXECUTE FUNCTION update_zone_serial_on_change();
