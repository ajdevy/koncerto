#!/usr/bin/env node

import { createInterface } from "node:readline";
import { spawn } from "node:child_process";
import { rmSync } from "node:fs";
import { join } from "node:path";
import { accessSync, constants } from "node:fs";

const args = process.argv.slice(2);
let model = null;
let variant = null;

for (let i = 0; i < args.length; i += 1) {
  const arg = args[i];
  if (arg === "--model" && i + 1 < args.length) {
    model = args[i + 1];
    i += 1;
    continue;
  }
  if (arg === "--variant" && i + 1 < args.length) {
    variant = args[i + 1];
    i += 1;
  }
}

let workingDirectory = process.cwd();
let started = false;
let turnId = 0;
const openCodeCommand = resolveOpenCodeCommand();

const rl = createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
});

rl.on("line", async (line) => {
  if (!line.trim()) return;

  let message;
  try {
    message = JSON.parse(line);
  } catch {
    return;
  }

  const method = message.method;
  if (method === "thread/start") {
    const dir = message.params?.working_directory;
    if (typeof dir === "string" && dir.length > 0) {
      workingDirectory = dir;
    }
    return;
  }

  if (method !== "turn/start" || started) return;
  started = true;
  turnId += 1;

  const input = message.params?.input;
  if (typeof input !== "string" || input.trim().length === 0) {
    emit({
      jsonrpc: "2.0",
      method: "turn/failed",
      params: {
        thread_id: "opencode-bridge",
        turn_id: String(turnId),
      },
    });
    process.exitCode = 1;
    return;
  }

  emit({
    jsonrpc: "2.0",
    method: "session/started",
    params: {
      thread_id: "opencode-bridge",
      turn_id: String(turnId),
    },
  });

  cleanupArtifacts(workingDirectory, { removeReviewFiles: true });

  const childArgs = [...openCodeCommand.args, "run", "--format", "json", "--dir", workingDirectory];
  if (model) childArgs.push("--model", model);
  if (variant) childArgs.push("--variant", variant);
  childArgs.push("--dangerously-skip-permissions", input);

  const child = spawn(openCodeCommand.command, childArgs, {
    cwd: workingDirectory,
    env: process.env,
    stdio: ["ignore", "pipe", "pipe"],
  });

  child.stdout.on("data", (chunk) => process.stderr.write(chunk));
  child.stderr.on("data", (chunk) => process.stderr.write(chunk));

  child.on("error", (error) => {
    process.stderr.write(`${error.message}\n`);
  });

  child.on("close", (code) => {
    cleanupArtifacts(workingDirectory, { removeReviewFiles: false });
    emit({
      jsonrpc: "2.0",
      method: code === 0 ? "turn/completed" : "turn/failed",
      params: {
        thread_id: "opencode-bridge",
        turn_id: String(turnId),
      },
    });
  });
});

function emit(payload) {
  process.stdout.write(`${JSON.stringify(payload)}\n`);
}

function cleanupArtifacts(workingDirectory, options) {
  const removeReviewFiles = options.removeReviewFiles;
  const traceFiles = [
    join(workingDirectory, ".koncerto", "dispatch-trace-2026-06-30.jsonl"),
    join(workingDirectory, ".koncerto", "review-trace-2026-06-30.jsonl"),
    join(workingDirectory, ".koncerto", "deploy-trace-2026-06-30.jsonl"),
  ];
  for (const file of traceFiles) {
    safeRemove(file);
  }
  if (!removeReviewFiles) return;
  for (const file of [".review-attempt", ".review-exhausted", ".review-output", ".review-status"]) {
    safeRemove(join(workingDirectory, file));
  }
}

function safeRemove(path) {
  try {
    rmSync(path, { force: true });
  } catch {
    // Best-effort cleanup only.
  }
}

function resolveOpenCodeCommand() {
  const pathValue = process.env.PATH ?? "";
  for (const dir of pathValue.split(":")) {
    if (!dir) continue;
    const candidate = join(dir, "opencode");
    try {
      accessSync(candidate, constants.X_OK);
      return { command: candidate, args: [] };
    } catch {
      // Try next path entry.
    }
  }
  return { command: "npx", args: ["--yes", "@opencode-ai/cli"] };
}
