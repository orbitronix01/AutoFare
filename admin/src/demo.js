// Sample data for previewing the portal without a database. Only ever loaded by
// the dev server with ?demo in the URL — see supabase.js. Never in a build.

const DAY = 86400000;
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString();
const ME = "00000000-0000-0000-0000-000000000001";
const R1 = "00000000-0000-0000-0000-000000000002";
const R2 = "00000000-0000-0000-0000-000000000003";

const admins = [
  { user_id: ME, name: "Oday", role: "owner", active: true, created_at: iso(40 * DAY) },
  { user_id: R1, name: "Abu Khaled", role: "reseller", active: true, created_at: iso(30 * DAY) },
  { user_id: R2, name: "Sami Haddad", role: "reseller", active: true, created_at: iso(12 * DAY) },
];

const customers = ["Abu Ahmad 0791234567", "Mahmoud K.", "Yazan 0785550190", null, "Rami Taxi", "Omar S.", "Fadi 0772221133", null, "Khalil", "Bashar"];
const ids = ["11f456e1a0b2c3d4", "a9c03e77d1e2f3a4", "5be2d0c4b5a69788", "c0ffee12aa55bb66", "77de9a01cc33dd44", "3fa4b2c1ee99ff00"];
const letters = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const code = (i) => Array.from({ length: 12 }, (_, k) => letters[(i * 7 + k * 13) % 31]).join("");

const codes = Array.from({ length: 18 }, (_, i) => {
  const used = i % 3 !== 0 && i < 14;
  const deleted = i === 15 || i === 17;
  return {
    id: `c${i}`,
    code: code(i + 1),
    months: [1, 1, 3, 1, 6, 1, 12, 1, 3][i % 9],
    created_by: [ME, R1, R2][i % 3],
    customer: customers[i % customers.length],
    created_at: iso((i * 2.3 + 1) * DAY),
    redeemed_device: used ? ids[i % ids.length] : null,
    redeemed_at: used ? iso((i * 2.3) * DAY) : null,
    for_device: null,
    deleted,
    deleted_at: deleted ? iso(DAY) : null,
  };
});

// one code per driver: a phone's older codes were deleted when it entered a newer one
for (const c of codes)
  if (c.redeemed_device && codes.some((o) => o.redeemed_device === c.redeemed_device && o.redeemed_at > c.redeemed_at)) c.deleted = true;

const devices = ids.map((id, i) => ({
  device_id: id,
  expires_at: iso([-25, -3, -60, 4, 9, -170][i] * DAY),
  first_seen: iso((30 - i * 3) * DAY),
  last_seen: iso([0.002, 0.1, 0.8, 6, 0.02, 2][i] * DAY),
  app_version: "0.5",
}));

const stats = {
  codes_created: 18, codes_redeemed: 10, codes_unused: 6, codes_deleted: 2, months_sold: 31,
  activations: 6, renewals: 4, devices_total: 6, devices_active: 4, devices_online: 4,
  by_month: ["05", "06", "07", "08", "09", "10"].map((m, i) => ({
    month: `2026-${m}`, created: [0, 2, 3, 4, 3, 6][i], activated: [0, 1, 1, 2, 1, 1][i], renewed: [0, 0, 1, 1, 1, 1][i],
  })),
  by_reseller: [
    { user_id: R1, name: "Abu Khaled", role: "reseller", active: true, created: 6, redeemed: 4, months: 11 },
    { user_id: ME, name: "Oday", role: "owner", active: true, created: 6, redeemed: 3, months: 9 },
    { user_id: R2, name: "Sami Haddad", role: "reseller", active: true, created: 6, redeemed: 3, months: 11 },
  ],
};

// ?demo=reseller signs in as Abu Khaled and shows only what the database
// policies would let a reseller read (see 0002_codes.sql)
const asReseller = new URLSearchParams(location.search).get("demo") === "reseller";
const USER = asReseller ? R1 : ME;
// the codes_read policy hides deleted codes from every admin
const myCodes = (asReseller ? codes.filter((c) => c.created_by === R1) : codes).filter((c) => !c.deleted);
const myIds = new Set(myCodes.map((c) => c.redeemed_device).filter(Boolean));
const tables = asReseller
  ? { admins: admins.filter((a) => a.user_id === R1), codes: myCodes, devices: devices.filter((d) => myIds.has(d.device_id)) }
  : { admins, codes: myCodes, devices };

function resellerStats() {
  const used = myCodes.filter((c) => c.redeemed_device);
  const devs = tables.devices;
  const live = devs.filter((d) => new Date(d.expires_at) > Date.now());
  const months = used.reduce((n, c) => n + c.months, 0);
  const r = { user_id: R1, name: "Abu Khaled", role: "reseller", active: true, created: myCodes.length, redeemed: used.length, months };
  return {
    ...stats,
    codes_created: myCodes.length, codes_redeemed: used.length,
    codes_unused: myCodes.filter((c) => !c.redeemed_device).length,
    codes_deleted: 0,
    months_sold: months,
    activations: myIds.size, renewals: used.length - myIds.size,
    devices_total: devs.length, devices_active: live.length, devices_online: live.length,
    by_month: stats.by_month.map((m) => ({ ...m, created: Math.round(m.created / 3), activated: Math.round(m.activated / 2), renewed: Math.round(m.renewed / 2) })),
    by_reseller: [r],
  };
}

function query(rows) {
  let out = [...rows];
  const q = {
    select: () => q,
    order: () => q,
    limit: (n) => ((out = out.slice(0, n)), q),
    eq: (k, v) => ((out = out.filter((r) => r[k] === v)), q),
    not: (k) => ((out = out.filter((r) => r[k] != null)), q),
    // only the one shape the portal uses: "a.not.is.null,b.not.is.null"
    or: (f) => ((out = out.filter((r) => f.split(",").some((p) => r[p.split(".")[0]] != null))), q),
    maybeSingle: () => Promise.resolve({ data: out[0] ?? null, error: null }),
    then: (res) => Promise.resolve({ data: out, error: null }).then(res),
  };
  return q;
}

export const demoClient = {
  auth: {
    getSession: () => Promise.resolve({ data: { session: { user: { id: USER }, access_token: "demo" } } }),
    onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
    signOut: () => Promise.resolve(),
    signInWithPassword: () => Promise.resolve({ error: null }),
  },
  from: (t) => query(tables[t] ?? []),
  rpc: (name, args) => {
    if (name === "admin_stats") return Promise.resolve({ data: asReseller ? resellerStats() : stats, error: null });
    if (name === "create_codes") {
      const list = tables.codes;
      const dev = args.p_device;
      // a renewal deletes the driver's older codes, as create_codes() does
      if (dev) for (let i = list.length - 1; i >= 0; i--) if (list[i].redeemed_device === dev || list[i].for_device === dev) list.splice(i, 1);
      const made = Array.from({ length: args.p_count }, (_, i) => ({
        id: `n${Date.now()}${i}`, code: code(40 + list.length + i), months: args.p_months, created_by: USER,
        customer: args.p_customer, created_at: new Date().toISOString(), redeemed_device: null, redeemed_at: null,
        for_device: dev ?? null, deleted: false,
      }));
      list.unshift(...made);
      return Promise.resolve({ data: made, error: null });
    }
    if (name === "delete_code") {
      const list = tables.codes;
      list.splice(list.findIndex((c) => c.id === args.p_id), 1);
    }
    return Promise.resolve({ data: null, error: null });
  },
};
