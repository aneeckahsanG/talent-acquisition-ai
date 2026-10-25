-- SHA-256 of the letter text at the moment it was sent, so later tampering can be detected
ALTER TABLE offer_letter ADD COLUMN letter_hash VARCHAR(64);
