# Mollie MCP — enable on demand, not by default

This repo does **not** keep the Mollie MCP server enabled. Its schema loads
into every session where it's configured, whether that session touches
payments or not, and the backend's own SDK path already handles all
payment creation and webhook work in code (`MOLLIE_API_KEY`,
`MOLLIE_WEBHOOK_URL` — see `backend/CLAUDE.md`). The MCP is only useful for
a different, rarer job: reading **live Mollie account state** directly —
payment status lookups, customer lookups, debugging a webhook against the
real dashboard — during an investigation session.

Enable it for that one session, do the work, then remove it again.

## The credential — and why it isn't `MOLLIE_API_KEY`

The MCP authenticates against Mollie's own hosted server
(`https://mcp.mollie.com/mcp`) using a **personal organization OAuth
token**, not the backend's API key:

- `MOLLIE_API_KEY` (backend/`.env`) — authorizes the Spring Boot app's SDK
  calls to create payments and refunds. App-runtime credential, scoped to
  this codebase.
- `MOLLIE_API_OAUTH_ORG_TOKEN` (this doc) — authorizes **Claude Code
  itself** to query your Mollie organization's live account state on your
  behalf. A personal Claude Code credential, not an app credential. It does
  not belong in `backend/.env`.

Get it from the Mollie dashboard → **Developers → OAuth / API settings**
for your organization. Store it in your **user-level shell environment**
(PowerShell profile or Windows user environment variables), not in any
file inside this repo, so `npx mcp-remote` can see it when the server
starts.

## Root cause of the original timeout

Earlier attempts to use this server failed with `CONNECT_TIMEOUT`. The
cause: `MOLLIE_API_OAUTH_ORG_TOKEN` was unset, so the
`Authorization: Bearer ${MOLLIE_API_OAUTH_ORG_TOKEN}` header the client
sent was empty — the connection hung rather than failing fast with a clean
auth error. Setting the variable before the server starts resolves it;
nothing about the network path or `mcp.mollie.com` itself was broken.

## Enabling it for a session

1. Set `MOLLIE_API_OAUTH_ORG_TOKEN` in your user-level environment (not
   this repo).
2. Add this block to `.mcp.json`'s `mcpServers`:

   ```json
   "Mollie": {
     "command": "npx",
     "args": [
       "mcp-remote",
       "https://mcp.mollie.com/mcp",
       "--header",
       "Authorization: Bearer ${MOLLIE_API_OAUTH_ORG_TOKEN}"
     ]
   }
   ```

3. Add `"Mollie"` back to `enabledMcpjsonServers` in
   `.claude/settings.local.json`.
4. Restart the session so the MCP connects.
5. When the investigation is done, remove both additions — back to the
   empty state this repo defaults to.

## General principle

Any MCP server with per-session overhead and rare use gets **documented,
not left enabled**. The option is preserved by this doc, not by the
config. See the "Context discipline" section of the root `CLAUDE.md`.
