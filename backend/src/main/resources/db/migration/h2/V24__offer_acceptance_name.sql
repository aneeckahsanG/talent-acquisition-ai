-- The full name the candidate typed to accept the offer (their electronic acceptance)
ALTER TABLE offer_response_token ADD COLUMN signed_name VARCHAR(255);
