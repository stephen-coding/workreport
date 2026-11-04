// Protocol smoke test for the VS Code Shell: boots the real Core over out/rpc.js.
// VS Code Shell 的协议冒烟：通过 out/rpc.js 启动真实 Core 并跑一轮对话。
//
// Usage: npm run compile && npm run smoke

const http = require("http");
const os = require("os");
const path = require("path");
const { RpcConnection } = require("../out/rpc");

const coreHome = process.env.AGENT_CORE_HOME || path.resolve(__dirname, "..", "..", "core");
const python = process.env.AGENT_PYTHON || (process.platform === "win32" ? "python" : "python3");
const coreExe = process.env.AGENT_CORE_EXE;
const systemPrompts = [];

function startMockLlm() {
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", () => {
      try {
        const parsed = JSON.parse(body);
        const messages = parsed.messages || [];
        if (messages.length > 0) {
          systemPrompts.push(String(messages[0].content || ""));
        }
      } catch {
        // ignore malformed mock input
      }
      const chunks = [
        { choices: [{ delta: { content: "RPC SMOKE OK" } }] },
        { choices: [{ delta: {}, finish_reason: "stop" }] },
      ];
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      for (const chunk of chunks) {
        res.write(`data: ${JSON.stringify(chunk)}\n\n`);
      }
      res.write("data: [DONE]\n\n");
      res.end();
    });
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => resolve(server));
  });
}

function main() {
  return startMockLlm().then((server) => new Promise((resolve, reject) => {
    const port = server.address().port;
    const dataDir = path.join(os.tmpdir(), `agent-vscode-smoke-${Date.now()}`);
    const connection = new RpcConnection(coreExe
      ? { executable: coreExe, projectId: "smoke-vscode", dataDir }
      : { python, coreHome, projectId: "smoke-vscode", dataDir });

    let finished = false;
    let finishCount = 0;
    let token = "";
    const timer = setTimeout(() => fail(new Error("timed out waiting for chat/finish")), 60000);

    function cleanup() {
      clearTimeout(timer);
      connection.dispose();
      server.close();
    }

    function fail(error) {
      if (finished) {
        return;
      }
      finished = true;
      cleanup();
      reject(error);
    }

    connection.on("stderr", (chunk) => {
      if (chunk.toLowerCase().includes("traceback")) {
        process.stderr.write(`[core] ${chunk}`);
      }
    });
    connection.on("exit", (code) => fail(new Error(`core exited early (${code})`)));
    connection.on("notification", (message) => {
      if (message.method === "core/hello") {
        console.log("OK core/hello", JSON.stringify(message.params.capabilities));
        connection.notify("settings/update", {
          provider: "openai_compat",
          base_url: `http://127.0.0.1:${port}`,
          model: "mock",
        });
        connection.notify("ide/hello", { protocolVersion: "1.0", ide: "vscode-smoke" });
        connection.request("chat/start", {
          sessionId: "smoke-vscode",
          message: "打个招呼",
          contextSnapshot: { project_root: coreHome },
        }).then((response) => {
          if (response.ok !== true) {
            fail(new Error(`chat/start failed: ${JSON.stringify(response.error)}`));
          }
        }).catch(fail);
      } else if (message.method === "chat/token") {
        token += String(message.params.delta || "");
      } else if (message.method === "chat/finish") {
        if (!token.includes("RPC SMOKE OK")) {
          fail(new Error(`unexpected streamed text: ${token}`));
          return;
        }
        finishCount += 1;
        if (finishCount === 1) {
          console.log("OK chat/token + chat/finish:", token.trim());
          // Second round: the daily-report skill must be injected into the prompt.
          // 第二轮：验证日报 Skill 已注入 system prompt。
          token = "";
          connection.request("chat/start", {
            sessionId: "smoke-vscode",
            message: "请生成今天的日报",
            contextSnapshot: { project_root: coreHome },
          }).catch(fail);
          return;
        }
        if (!systemPrompts.some((prompt) => prompt.includes("daily-report"))) {
          fail(new Error("daily-report skill was not injected"));
          return;
        }
        console.log("OK skill injection: daily-report");
        finished = true;
        cleanup();
        resolve();
      } else if (message.method === "chat/error") {
        fail(new Error(`chat/error: ${JSON.stringify(message.params.error)}`));
      }
    });
  }));
}

main()
  .then(() => {
    console.log("VSCODE RPC SMOKE PASS");
    process.exit(0);
  })
  .catch((error) => {
    console.error("FAIL:", error.message);
    process.exit(1);
  });
