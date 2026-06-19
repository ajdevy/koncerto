---
stepsCompleted: [1, 2]
inputDocuments: []
session_topic: "Demo Recording Feature - Screen Recording & Screenshot Capture for Task Verification"
session_goals: "Create a demo recording feature for completed tasks that captures screen recordings (video primary, screenshots fallback) for UX features and HTML-formatted API/log reports for technical tasks, runs after code review, optimizes disk space, uses opencode free models for AI processing, includes retry logic with exponential backoff (3 retries) and marks ticket as blocked if recording fails completely"
selected_approach: "ai-recommended"
techniques_used: ["SCAMPER Method", "First Principles Thinking", "Cross-Pollination", "Constraint Mapping", "Chaos Engineering", "Solution Matrix"]
ideas_generated: []
context_file: ""
---

# Brainstorming Session Results

**Facilitator:** aj
**Date:** 2026-06-18


## Session Overview

**Topic:** Demo Recording Feature - Screen Recording & Screenshot Capture for Task Verification
**Goals:** Design a feature that records end-to-end demos of completed tasks (screen recording preferred, screenshots as fallback) that runs after successful code review, capturing both UI interactions and technical verification (API calls, deployments)

### Context Guidance

_None provided_

### Session Setup

Based on our conversation, we're focusing on creating a demo recording feature that:
1. Captures screen recordings (video) as primary, screenshots as fallback for UX tasks
2. For UX tasks: Creates an HTML report with the video embedded, explanation text, and a text timeline at the bottom showing what happens at each timestamp in the recording
3. For technical tasks without UX: captures logs of curl/API calls, deployments, verification commands - formatted as nice, readable HTML with comments explaining what's happening and what's being called
4. Runs automatically after successful code review
5. Optimizes for minimal disk space while maintaining good quality
6. Uses opencode free models for any AI processing (e.g., video analysis, timeline generation) - configurable in workflow.md
7. Error handling: If demo recording fails, retry with exponential backoff for 3 attempts; if all retries fail, mark the ticket as blocked (unable to record demo)
8. HTML reports include clear step-by-step instructions on how to reproduce the demo yourself

**Does this accurately capture what you want to achieve?**

## Technique Selection

**Approach:** AI-Recommended Techniques
**Analysis Context:** Demo recording feature with focus on screen capture for UX, HTML-formatted API logs for technical tasks, HTML reports with embedded video + timeline + reproduction instructions, post-code-review automation, disk space optimization, opencode free models configurable in workflow.md, retry logic with exponential backoff (3 retries), mark ticket as blocked on failure

**Recommended Techniques:**

- **SCAMPER Method:** Systematic creativity through seven lenses for methodical feature design - Substitute video formats, Combine recording+reporting, Adapt existing tools, Modify compression, Put to other uses, Eliminate complexity, Reverse workflow
- **First Principles Thinking:** Strip away assumptions about "how demos should work" - rebuild from fundamentals: what *actually* needs to be captured, what's the minimum viable demo, what's the physics of screen recording
- **Cross-Pollination:** Transfer solutions from other domains: CI/CD pipeline artifacts, test reporting tools (Allure, ReportPortal), screen recording libraries (ffmpeg, OBS), HTML report generators, observability stacks
- **Constraint Mapping:** Identify ALL constraints: disk space, code review integration points, opencode model config, retry/backoff requirements, HTML standards, cross-platform recording, video formats
- **Chaos Engineering:** Stress-test the design - what if recording fails mid-way? What if disk fills up? What if code review passes but demo times out? What if opencode model is unavailable? Build anti-fragility into the feature
- **Solution Matrix:** Create systematic grid of: Task Type (UX vs Technical) × Capture Method (Video vs Screenshots vs HTML) × Output Format × Retry Strategy × AI Processing → find optimal combinations for each scenario

**AI Rationale:** This sequence progresses from foundational design (SCAMPER, First Principles) through idea generation (Cross-Pollination, Constraint Mapping) to refinement and robustness (Chaos Engineering, Solution Matrix), covering all aspects of the feature requirements systematically.