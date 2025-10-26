Team Convention & Workflow

1. Git Branch Naming Convention

Follow consistent naming for clarity and tracking.

Format:

feature/GRAD-XX-description
fix/GRAD-XX-bug-description
hotfix/GRAD-XX-critical-issue

Examples:

feature/GRAD-12-user-authentication
fix/GRAD-18-login-validation
hotfix/GRAD-25-deployment-error

Rules:

Always branch from develop, never from main.

Use lowercase with hyphens.

Branch name must include Jira issue ID.

2. Commit Message Convention

Use clear, traceable messages linked to Jira tickets.

Format:

GRAD-XX: <short description>

Examples:

GRAD-12: Implement JWT-based authentication
GRAD-18: Fix null pointer issue in login service
GRAD-25: Resolve Docker build failure

Rules:

One logical change per commit.

Use imperative mood (“Add” not “Added”).

Keep under 72 characters when possible.

3. Pull Request (PR) Rules

Ensure quality and traceability in all merges.

Requirements:

Branch must be up-to-date with develop using rebase, not merge.

PR title must include Jira ID.

Minimum 1 reviewer approval before merge.

All CI/CD checks must pass.

Delete branch and squash commit after merge.

Example:

PR Title: GRAD-12: Add user authentication feature

4. Development Workflow

Main Branches:

main → production-ready code only.

develop → integration branch for new features.

Flow:

Developer picks a Jira task (e.g., GRAD-12).

Create branch from develop → feature/GRAD-12-description.

Develop locally and commit using Jira ID.

Rebase with latest develop before opening PR.

Open PR → review → merge into develop.

Jira status updated automatically or manually:

To Do → In Progress → In Review → Done

Example Flow:

Jira Task: GRAD-12
Branch: feature/GRAD-12-user-authentication
Commit: GRAD-12: Implement JWT authentication
PR: GRAD-12: Add authentication endpoints

This workflow ensures consistency, traceability, and minimal merge conflicts across both backend and frontend repositories.

