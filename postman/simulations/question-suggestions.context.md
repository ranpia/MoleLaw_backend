# Initial question suggestions

Run from the repository root:

```powershell
postman simulation run postman/simulations/question-suggestions.sim.yaml --port 14040 --no-history --output ndjson
$env:QUESTION_SUGGESTION_SIMULATION_URL='http://127.0.0.1:14040'
.\gradlew.bat initialQuestionSuggestionSimulationTest
```

The caller uses the real OpenAI HTTP client and isolated recommendation ChatClient against `/openai/v1/chat/completions`. The fixture requires a synthetic key, strict JSON schema, two messages, no tools and low reasoning. It checks success, malformed model content and dependency outage. No real provider calls, retrieval, embeddings or persistent consultation data are used. Request bodies must remain synthetic.
