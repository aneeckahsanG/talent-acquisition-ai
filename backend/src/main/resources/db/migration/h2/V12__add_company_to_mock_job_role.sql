ALTER TABLE mock_job_role ADD COLUMN IF NOT EXISTS company VARCHAR(255);

-- Assign companies to the seeded roles
UPDATE mock_job_role SET company = 'TechCorp Malaysia' WHERE title = 'Senior Software Engineer';
UPDATE mock_job_role SET company = 'GrowthLabs Sdn Bhd' WHERE title = 'Product Manager';
UPDATE mock_job_role SET company = 'DataVision Analytics' WHERE title = 'Data Scientist';
UPDATE mock_job_role SET company = 'CreativeEdge Studio' WHERE title = 'UX/UI Designer';
