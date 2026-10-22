# CS5013 Module 6 hands-on

- `m6-lab-a/` -- Session 6A: RAG over a local Java repo. Write-up: `m6-lab-a/6A-notes.md`.
- `m6-lab-b/` -- Session 6B: ReAct agent with two tools, step and token budgets. Write-up: `m6-lab-b/6B-reflection.md`, traces in `m6-lab-b/traces/`.

Both labs run against a local Ollama model (`llama3.2:3b`). In each lab folder:

    make langchain4j      # one-time, needs Maven
    make test
    make run              # 6B: make run ARGS='q="..." steps=3 tokens=1500'
