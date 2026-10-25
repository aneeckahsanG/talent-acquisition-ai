ALTER TABLE interview_schedule ADD COLUMN interviewer_id BIGINT;
ALTER TABLE interview_schedule ADD CONSTRAINT fk_interview_interviewer FOREIGN KEY (interviewer_id) REFERENCES app_user(id);

-- denormalised onto the booking so overlap checks can be done per interviewer
ALTER TABLE interview_slot ADD COLUMN interviewer_id BIGINT;
