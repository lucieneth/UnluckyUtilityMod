# Deploying the Unlucky registry

Everything here runs from the `server/` folder, in a normal terminal (PowerShell is
fine). Steps 1–3 are one-time; after that, shipping a change is just `npx wrangler
deploy`.

You need Node installed — you already have it, `npx wrangler` worked when we tested.

> **Already set up?** If you've deployed before, you only need to redeploy the new
> code: `npx wrangler deploy`. There's no secret to set anymore — the registry no
> longer does the Mojang handshake (Mojang's WAF blocks that call from Cloudflare's
> IPs), so it's now a simple public cape/marker directory. See `src/index.js`'s header
> for the full reasoning.

---

## 1. Log in to Cloudflare

```
cd C:\Users\Lucien\Desktop\UnluckyClient\server
npx wrangler login
```

A browser tab opens — click **Allow**. Check the right account:

```
npx wrangler whoami
```

If you have more than one, make sure the one shown is the account that holds
`unlucky.life`.

---

## 2. Create the KV namespace (the database)

```
npx wrangler kv namespace create REGISTRY
```

It prints an `id = "..."`. Copy it, open `server/wrangler.toml`, and paste it over the
placeholder:

```toml
[[kv_namespaces]]
binding = "REGISTRY"
id = "REPLACE_WITH_KV_NAMESPACE_ID"   <-- put the real id here
```

Leave `binding = "REGISTRY"` exactly as it is — that name is what the worker code looks
for.

---

## 3. Deploy

```
npx wrangler deploy
```

Expected tail of the output:

```
Uploaded unlucky-registry (1.2 sec)
Deployed unlucky-registry triggers (0.8 sec)
  api.unlucky.life (custom domain)
```

### If it errors about the custom domain

The `[[routes]]` block claims `api.unlucky.life`, which only works if `unlucky.life` is
already a zone in this Cloudflare account. If it can't find the zone: delete that
`[[routes]]` block, deploy again, and it'll give you a `…workers.dev` URL — send me that
and I'll make it the client's default.

---

## 4. Check it's alive

```
curl https://api.unlucky.life/v1/capes
```

`{"groups":["mojang","unlucky"]}` means it's live. The client already defaults to that
URL, so just launch `run.bat`, join a server with the **UnluckyUsers** module on, pick a
cape in the Capes module, and it publishes itself. Your friends running the client see
your cape and the ✦ next to your name.

---

## Shipping a change later

Edit `src/index.js`, then `npx wrangler deploy`. That's the whole loop. To test without
deploying, run it locally (KV is simulated on disk, no account needed):

```
npx wrangler dev          # terminal 1, from server/
..\run-local-api.bat      # terminal 2, from the repo root
```

`run-local-api.bat` points the client at `http://127.0.0.1:8787` instead of production.

## Watching it run

```
npx wrangler tail
```

Every publish and every `GET /v1/users` scrolls past — first place to look if capes stop
showing up.

## What's stored

```
npx wrangler kv key list --binding REGISTRY --remote
npx wrangler kv key get roster --binding REGISTRY --remote
```

One `user:<uuid>` record per user plus a single `roster` blob. Nothing secret — no
tokens, no passwords. Just uuid → {name, cape, colour}.
