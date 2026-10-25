-- A start time may be booked once per interviewer, not once overall
ALTER TABLE interview_slot DROP CONSTRAINT uq_interview_slot_start;
ALTER TABLE interview_slot ADD CONSTRAINT uq_slot_interviewer_start UNIQUE (interviewer_id, start_time);
