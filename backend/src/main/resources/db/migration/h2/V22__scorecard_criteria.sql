-- Ratings are stored per named criterion so each interview type can be scored on its own criteria
CREATE TABLE interview_scorecard_rating (
    scorecard_id BIGINT      NOT NULL,
    criterion    VARCHAR(40) NOT NULL,
    rating       INT         NOT NULL,
    PRIMARY KEY (scorecard_id, criterion),
    CONSTRAINT fk_scorecard_rating FOREIGN KEY (scorecard_id) REFERENCES interview_scorecard(id)
);

-- Carry over scorecards saved with the old fixed columns
INSERT INTO interview_scorecard_rating (scorecard_id, criterion, rating) SELECT id, 'TECHNICAL', technical FROM interview_scorecard;
INSERT INTO interview_scorecard_rating (scorecard_id, criterion, rating) SELECT id, 'COMMUNICATION', communication FROM interview_scorecard;
INSERT INTO interview_scorecard_rating (scorecard_id, criterion, rating) SELECT id, 'PROBLEM_SOLVING', problem_solving FROM interview_scorecard;
INSERT INTO interview_scorecard_rating (scorecard_id, criterion, rating) SELECT id, 'CULTURE_FIT', culture_fit FROM interview_scorecard;

ALTER TABLE interview_scorecard DROP COLUMN technical;
ALTER TABLE interview_scorecard DROP COLUMN communication;
ALTER TABLE interview_scorecard DROP COLUMN problem_solving;
ALTER TABLE interview_scorecard DROP COLUMN culture_fit;

-- Practical details captured on phone screens
ALTER TABLE interview_scorecard ADD COLUMN notice_period VARCHAR(100);
ALTER TABLE interview_scorecard ADD COLUMN expected_salary VARCHAR(100);
