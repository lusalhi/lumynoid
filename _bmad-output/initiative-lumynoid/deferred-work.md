- source_plan: `_bmad-output/initiative-lumynoid/tickets/epic-chat-dan-provider/tickets/story-repo-publik-berlisensi-dengan-gerbang-ci-plan.md`
  summary: Prove the three CI gates on GitHub: create `main`, open throw-away PRs (Firebase dependency, reused schema number), check they fail, close them, and confirm `licenseInfo` is Apache-2.0.
  evidence: Step 3 forbids push and remote operations, so the gates were proven only locally (self-tests, a local Firebase and local-jar negative run); the `schema-claims` job and its `gh api` open-PR scan have never run on GitHub.
