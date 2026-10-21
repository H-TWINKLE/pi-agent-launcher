// Pi Agent Launcher — IDE bridge extension.
// bridge-version: 1.1.0
//
// This file is installed automatically by the Pi Agent Launcher JetBrains plugin into the user
// extension directory (<agent-dir>/extensions, i.e. ~/.pi/agent/extensions by default).
//
// It is inert unless pi was started with `--ide-bridge=<url>`: every other session loads it and
// then does nothing, so it never interferes with pi runs that did not come from the IDE.

const BRIDGE_VERSION = "1.1.0";
const FLAG = "ide-bridge";

/** Tools that never modify a file even though they may carry a path. */
const NON_MUTATING_TOOLS = new Set(["read", "grep", "find", "ls", "bash", "powershell"]);

/** Path-like fields used by built-in and third-party file tools. */
const PATH_FIELDS = ["path", "file_path", "filePath", "target_file", "filename"];

function pathFromInput(input) {
  if (!input || typeof input !== "object") return undefined;
  for (const field of PATH_FIELDS) {
    const value = input[field];
    if (typeof value === "string" && value.trim() !== "") return value;
  }
  return undefined;
}

export default function piAgentLauncherBridge(pi) {
  pi.registerFlag(FLAG, {
    description: "IDE bridge URL used by the Pi Agent Launcher plugin",
    type: "string",
  });

  const bridgeUrl = () => {
    const url = pi.getFlag(FLAG);
    return typeof url === "string" && url.startsWith("http") ? url : undefined;
  };

  /** Fire and forget: reporting must never delay a tool call or the agent loop. */
  const report = (lines) => {
    const url = bridgeUrl();
    if (!url) return;
    void fetch(url, {
      method: "POST",
      headers: { "content-type": "text/plain; charset=utf-8" },
      body: ["v=1", ...lines].join("\n"),
    }).catch(() => {});
  };

  // The session id lets the IDE keep concurrent Pi instances apart: each one settles its own
  // change counter, so "Pi modified N files" never mixes the work of two sessions.
  const sessionOf = (ctx) => {
    try {
      return ctx.sessionManager.getSessionId();
    } catch {
      return undefined;
    }
  };

  pi.on("tool_call", (event, ctx) => {
    if (NON_MUTATING_TOOLS.has(event.toolName)) return;
    const file = pathFromInput(event.input);
    if (!file) return;
    const session = sessionOf(ctx);
    report([
      `tool=${event.toolName}`,
      ...(session ? [`session=${session}`] : []),
      `cwd=${ctx.cwd}`,
      `file=${file}`,
    ]);
  });

  pi.on("agent_settled", (_event, ctx) => {
    const session = sessionOf(ctx);
    report([...(session ? [`session=${session}`] : []), "settled=1", `cwd=${ctx.cwd}`]);
  });
}
