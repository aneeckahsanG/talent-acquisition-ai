-- ============================================================
-- V2: Seed data for demo purposes
-- NOTE: app_user rows are intentionally omitted here.
-- Demo users are created on startup by DataInitializer.java
-- using BCryptPasswordEncoder so the hash is always correct.
-- Default password for all seeded users: password123
-- ============================================================

INSERT INTO job_requisition (title, department, location, description, required_skills, experience_level, status, created_by) VALUES
('Senior Backend Engineer (Java)', 'Engineering', 'Kuala Lumpur', 'We are looking for a Senior Backend Engineer with strong Java and Spring Boot experience to lead development of our core platform services. Responsibilities include designing scalable microservices, mentoring junior engineers, and collaborating with product teams.', 'Java, Spring Boot, Microservices, PostgreSQL, AWS, REST APIs, System Design', 'SENIOR', 'OPEN', 2),
('Data Analyst', 'Analytics', 'Kuala Lumpur', 'Seeking a Data Analyst to support business teams with reporting, dashboards, and ad-hoc analysis. Strong SQL skills and experience with BI tools required.', 'SQL, Excel, Power BI, Tableau, Python, Data Visualization', 'MID', 'OPEN', 2),
('AI/ML Engineer', 'Engineering', 'Kuala Lumpur (Hybrid)', 'Join our AI team to build and deploy machine learning models and LLM-powered features into production. Experience with Python, model deployment, and prompt engineering preferred.', 'Python, Machine Learning, LLM, PyTorch, Cloud Deployment, APIs', 'MID', 'OPEN', 2);
