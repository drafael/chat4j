# Model Context Protocol (MCP)

Chat4J can use MCP tools in workspace-backed **Agent Mode**. Configure servers under **Settings → MCP**. MCP is not used by ordinary chat and no server is started at application startup.

## Supported protocol and transports

Chat4J negotiates MCP `2025-06-18` and supports:

- **Stdio** — a native executable or bare command resolved through `PATH`, plus ordered arguments.
- **Streamable HTTP** — one absolute HTTP(S) endpoint. The complete configured path and query are preserved and redirects are not followed.

Deprecated two-endpoint HTTP+SSE, protocol downgrade, OAuth, resources, prompts, roots, sampling, elicitation, tasks, and returned image/audio/resource content are not supported.

## Stdio safety

Commands are launched directly without a shell. Relative executable paths containing separators and Windows `.cmd`/`.bat` launchers are rejected. On Windows configure a native interpreter explicitly, for example `node.exe` followed by the CLI script path. The working directory is the user's home directory, falling back to Chat4J's configuration directory.

Chat4J repeatedly discovers and terminates the launched process tree during shutdown and reports cleanup failures for mandatory retry. OS-level process-group and job-object behavior still requires packaged validation on each supported platform.

Configured stdio servers run with the current user's operating-system permissions and are **not sandboxed**. Only enable servers you trust.

## Credentials

HTTP header and stdio environment values are encrypted in Chat4J's credential vault. `mcp.json` stores only opaque references. Endpoint query strings are plaintext; put credentials in encrypted headers instead.

Saved values remain masked. Leaving a masked field unchanged retains it; replacing or clearing a value must be explicit. Chat4J redacts configured values from MCP diagnostics, approval presentation, and provider-bound tool results.

## Verification and tools

**Verify / Refresh** saves the current draft, initializes that exact server configuration, and lists every tool page. Newly discovered tools are enabled by default; disabling a tool persists its exact case-sensitive name.

Every enabled server must initialize and list tools before the first provider turn. An unavailable enabled server stops the Agent run with a repair/disable message.

## Approval and automatic execution

With **Run tools automatically** disabled, Chat4J asks before every MCP invocation. **Allow once** sends the original arguments; **Deny**, closing the dialog, Escape, or cancellation does not call the server.

Automatic execution should be enabled only for trusted servers. Model and tool output is untrusted and may cause tools to act with your user permissions.

## Lifecycle and troubleshooting

Connections are opened only for Verify or an Agent run. A long-running stdio connection can be reused until its connection settings change, the server is disabled/removed, the client fails, or Chat4J exits. Policy and tool-enable changes apply to the next run without interrupting an active run.

A timed-out or cancelled MCP call poisons its client. Chat4J does not retry an ambiguous tool call; a later explicit Verify/run creates a new connection.

For failures, check the executable/endpoint, disable unavailable servers, and verify again. Chat4J logs only sanitized server identity, transport, protocol, lifecycle state, and error category—not arguments or credential values.
