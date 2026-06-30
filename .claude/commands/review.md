Review the staged diff as a senior engineer on this team. For each change report:
- What's good (acknowledge it briefly)
- What's wrong or risky, and *why* (not just "improve error handling")
- What's missing for a production version
- Specific fixes with exact file/line placement, including boilerplate

Hold the conventions in CLAUDE.md. Flag any violation of the spec→resolver→domain
boundary, any LLM call inside a transaction, and any movement in the scoring
regression anchors.