/**
 * Unlucky registry.
 *
 * A public, cosmetic directory: "who runs Unlucky, and what cape/marker colour did
 * they pick?" Every client publishes its OWN uuid, and everyone reads the roster to
 * decorate the tab list and render capes.
 *
 * WHY NO ACCOUNT VERIFICATION. The proper way to prove identity is the Mojang
 * joinServer/hasJoined handshake — but Mojang's WAF blocks that call from Cloudflare's
 * datacenter IPs (it 403s), so it simply cannot run here. Rather than take on another
 * host, we lean on the fact that everything in this registry is cosmetic: the worst a
 * forged write can do is put a cape or a ✦ on a uuid that isn't the writer's. No token,
 * no account access, no money — and the marker is only ever visible to someone else
 * running this client with the module switched on, so it can't feed a server's
 * anti-cheat. For a friends' cape system that trade is the right one. If this ever needs
 * to be tamper-proof, the upgrade is profile-key signing (the client signs a challenge
 * with its Mojang-issued chat key; we verify against Mojang's public key, no egress) —
 * documented, not built.
 *
 * STORAGE (KV, bound as REGISTRY):
 *   user:<uuid>   {name, cape, color}   the source of truth for one user
 *   roster        {uuid: {cape,color}}  every user, denormalized for the read path
 *
 * KV writes are the scarce resource on the free plan (~1k/day), so every write is
 * change-guarded: republishing an unchanged profile — which is what a client does on
 * every startup — costs zero writes. Reads are served from one small blob, memoized
 * per isolate.
 */

const ROSTER_MEMO_MS = 15 * 1000;

const SHAREABLE_GROUPS = ['mojang', 'unlucky'];
/**
 * A cape is "which one did you pick in the Capes module", stored as `group:name` —
 * the registry hosts no textures. Only groups every client can resolve for itself are
 * shareable: `mojang:` streams from textures.minecraft.net, `unlucky:` from the public
 * GitHub cape repo. `custom` is rejected (those PNGs live on one disk). `none` clears it.
 * The character class stays tight: it's attacker-influenceable text that lands on other
 * people's screens.
 */
const CAPE_PATTERN = /^(mojang|unlucky):[\w .'\-]{1,48}$/;
const NAME_PATTERN = /^[\w]{1,16}$/;
const UUID_PATTERN = /^[0-9a-f]{32}$/;

function validCape(cape) {
	return cape === 'none' || CAPE_PATTERN.test(cape);
}

function json(body, status = 200, extra = {}) {
	return new Response(JSON.stringify(body), {
		status,
		headers: { 'content-type': 'application/json', ...extra },
	});
}

/** Undashed, lower-case uuid — the form Mojang's API and our keys use. */
function normalizeUuid(uuid) {
	return String(uuid).replace(/-/g, '').toLowerCase();
}

// ---------------------------------------------------------------- roster

let rosterCache = null;
let rosterCachedAt = 0;

async function readRoster(env) {
	const now = Date.now();
	if (rosterCache && now - rosterCachedAt < ROSTER_MEMO_MS) {
		return rosterCache;
	}
	rosterCache = (await env.REGISTRY.get('roster', 'json')) || {};
	rosterCachedAt = now;
	return rosterCache;
}

/**
 * Merges one user's public row into the roster, and only if it actually changed —
 * an unchanged republish (every client, every startup) must cost no KV write.
 *
 * Read-modify-write, so two updates in the same instant can lose one; that's
 * survivable: the per-user record is authoritative and the loser's next publish
 * repairs the roster.
 */
async function updateRoster(env, uuid, row) {
	const roster = (await env.REGISTRY.get('roster', 'json')) || {};
	if (JSON.stringify(roster[uuid]) === JSON.stringify(row)) {
		return;
	}
	roster[uuid] = row;
	await env.REGISTRY.put('roster', JSON.stringify(roster));
	rosterCache = roster;
	rosterCachedAt = Date.now();
}

/** The public row for a user: what other clients are allowed to see. */
function publicRow(record) {
	const row = {};
	if (record.cape) {
		row.cape = record.cape;
	}
	if (record.color !== undefined && record.color !== null) {
		row.color = record.color;
	}
	return row;
}

// ---------------------------------------------------------------- routes

/**
 * Publish (or update) your own profile. Body: {uuid, name, cape, color}. The uuid is
 * taken at face value — see the file header for why that's acceptable here. Everything
 * is validated so nothing weird reaches another client's screen, and writes are
 * change-guarded so honest clients cost ~zero KV.
 */
async function publish(request, env) {
	let body;
	try {
		body = await request.json();
	} catch {
		return json({ error: 'invalid body' }, 400);
	}
	const uuid = normalizeUuid(body.uuid || '');
	if (!UUID_PATTERN.test(uuid)) {
		return json({ error: 'valid uuid required' }, 400);
	}
	if (body.name !== undefined && !NAME_PATTERN.test(String(body.name))) {
		return json({ error: 'invalid name' }, 400);
	}
	if (body.cape !== undefined && !validCape(body.cape)) {
		return json({ error: 'cape must be none, or group:name from a shareable group', groups: SHAREABLE_GROUPS }, 400);
	}
	if (body.color !== undefined && !Number.isInteger(Number(body.color))) {
		return json({ error: 'color must be an integer 0xRRGGBB' }, 400);
	}

	const before = (await env.REGISTRY.get(`user:${uuid}`, 'json')) || {};
	const record = { ...before };
	if (body.name !== undefined) {
		record.name = String(body.name);
	}
	if (body.cape !== undefined) {
		if (body.cape === 'none') {
			delete record.cape;
		} else {
			record.cape = body.cape;
		}
	}
	if (body.color !== undefined) {
		record.color = Number(body.color) & 0xffffff; // RGB only
	}

	if (JSON.stringify(record) !== JSON.stringify(before)) {
		record.updated = Date.now();
		await env.REGISTRY.put(`user:${uuid}`, JSON.stringify(record));
		await updateRoster(env, uuid, publicRow(record));
	}

	return json({
		ok: true,
		uuid,
		cape: record.cape || 'none',
		color: record.color ?? null,
	});
}

/** Public, batched, cacheable — the OptiFine-capes read model. */
async function getUsers(url, env) {
	const raw = url.searchParams.get('uuids') || '';
	const wanted = raw.split(',').map(normalizeUuid).filter((u) => UUID_PATTERN.test(u)).slice(0, 100);
	const roster = await readRoster(env);
	const found = {};
	for (const uuid of wanted) {
		const row = roster[uuid];
		if (!row) {
			continue; // not one of ours: absent from the response
		}
		// `user: true` stands alone — a caped user and a bare user are both users
		found[uuid] = { user: true, ...row };
	}
	// short: a user can change their cape at any moment, and the client polls this
	return json({ users: found }, 200, { 'cache-control': 'public, max-age=30' });
}

export default {
	async fetch(request, env) {
		const url = new URL(request.url);
		const route = `${request.method} ${url.pathname}`;
		try {
			switch (route) {
				case 'PUT /v1/profile':
				case 'POST /v1/profile':
					return await publish(request, env);
				case 'GET /v1/users':
					return await getUsers(url, env);
				case 'GET /v1/capes':
					return json({ groups: SHAREABLE_GROUPS });
				default:
					return json({ error: 'not found' }, 404);
			}
		} catch (err) {
			console.error('registry error', err);
			return json({ error: 'internal error' }, 500);
		}
	},
};
