-- Availability can now belong to one interviewer. NULL keeps the old meaning: open to every interviewer.
ALTER TABLE availability_window ADD COLUMN interviewer_id BIGINT;
ALTER TABLE availability_window ADD CONSTRAINT fk_window_interviewer FOREIGN KEY (interviewer_id) REFERENCES app_user(id);
