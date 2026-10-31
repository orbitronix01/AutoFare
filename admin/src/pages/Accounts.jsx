import React, { useEffect, useState } from "react";
import { supabase, unwrap, fmtDate, priceOf, SUPABASE_URL } from "../supabase.js";
import { PageHead, Modal, Confirm, Avatar, Icon, CopyButton } from "../ui.jsx";

/** Owner only: make reseller logins, reset their passwords, switch them off. */
export default function Accounts({ me, onChanged }) {
  const [admins, setAdmins] = useState(null);
  const [stats, setStats] = useState({});
  const [error, setError] = useState(null);
  const [making, setMaking] = useState(false);
  const [open, setOpen] = useState(null);

  useEffect(() => {
    (async () => {
      try {
        setAdmins(unwrap(await supabase.from("admins").select("*").order("created_at")));
        const s = unwrap(await supabase.rpc("admin_stats"));
        setStats(Object.fromEntries(s.by_reseller.map((r) => [r.user_id, r])));
      } catch (e) {
        setError(e.message);
      }
    })();
  }, []);

  const list = admins ?? [];
  const resellers = list.filter((a) => a.role === "reseller");

  return (
    <>
      <PageHead
        title="Team Accounts"
        stats={[
          { icon: "users", value: resellers.filter((a) => a.active).length, label: "Active resellers" },
          { icon: "ticket", value: Object.values(stats).reduce((n, r) => n + r.created, 0), label: "Codes made by everyone" },
        ]}
      />
      <div className="toolbar">
        <span className="muted">Each reseller signs in here, makes codes, and sees only their own codes and numbers.</span>
        <span className="spacer" />
        <button className="btn lime" onClick={() => setMaking(true)}><Icon name="plus" size={16} /> New account</button>
      </div>

      {error && <p className="error">{error}</p>}
      {!admins ? (
        <p className="muted">Loading…</p>
      ) : (
        <div className="dir">
          {list.map((a, i) => {
            const s = stats[a.user_id] ?? { created: 0, redeemed: 0, months: 0 };
            const rate = s.created ? Math.round((s.redeemed / s.created) * 100) : 0;
            return (
              <div key={a.user_id} className={`dcard ${a.active ? "" : "dim"}`} onClick={() => a.user_id !== me.user_id && setOpen(a)}>
                <div className="dcard-top">
                  <Avatar text={a.name} tone={["lime", "sky", "clay", "stone"][i % 4]} />
                  <span className={`pill ${a.role === "owner" ? "lime" : a.active ? "sky" : ""}`}>
                    {a.role === "owner" ? "Owner" : a.active ? "Reseller" : "Switched off"}
                  </span>
                </div>
                <div>
                  <div className="dcard-name">{a.name}{a.user_id === me.user_id ? " (you)" : ""}</div>
                  <div className="dcard-sub">since {fmtDate(a.created_at)}</div>
                </div>
                <div className="kv">
                  <div><span>Codes made</span><b>{s.created}</b></div>
                  <div><span>Earned</span><b>{priceOf(s.months)}</b></div>
                </div>
                <div className="meter">
                  <div className="meter-track"><div className="meter-fill lime" style={{ width: `${rate}%` }} /></div>
                  {rate}% used
                </div>
              </div>
            );
          })}
        </div>
      )}

      {making && <NewAccount onClose={() => setMaking(false)} onMade={onChanged} />}
      {open && <AccountActions a={open} onClose={() => setOpen(null)} onChanged={onChanged} />}
    </>
  );
}

function AccountActions({ a, onClose, onChanged }) {
  const [mode, setMode] = useState(null);
  const [newPassword, setNewPassword] = useState(null);
  const [error, setError] = useState(null);

  if (newPassword)
    return (
      <Modal title="Password changed" subtitle={`Send it to ${a.name}.`} onClose={onClose}>
        <pre className="codebox">{newPassword}</pre>
        <div className="modal-foot"><CopyButton text={newPassword} className="btn lime" /></div>
      </Modal>
    );

  if (mode === "password")
    return (
      <Confirm
        title={`New password for ${a.name}`}
        body="At least 10 characters. Their old password stops working straight away."
        input={{ label: "Password", value: makePassword() }}
        yes="Set password"
        onClose={onClose}
        onYes={async (password) => {
          try {
            await callAdminUsers({ action: "set_password", user_id: a.user_id, password });
            setNewPassword(password);
          } catch (e) {
            setError(e.message);
          }
          setMode(null);
          return true;
        }}
      />
    );

  if (mode === "toggle")
    return (
      <Confirm
        title={a.active ? `Switch off ${a.name}?` : `Switch ${a.name} back on?`}
        body={a.active ? "They can no longer make codes or see anything here. Codes they already sold keep working." : "They can sign in and make codes again."}
        yes={a.active ? "Switch off" : "Switch on"}
        danger={a.active}
        onClose={onClose}
        onYes={async () => {
          const { error } = await supabase.rpc("set_admin_active", { p_user_id: a.user_id, p_active: !a.active });
          if (!error) return onChanged();
          setError(error.message);
          setMode(null);
          return true;
        }}
      />
    );

  return (
    <Modal title={a.name} subtitle={a.active ? "Reseller" : "Switched off"} onClose={onClose}>
      {error && <p className="error">{error}</p>}
      <div className="modal-foot">
        <button className={`btn ${a.active ? "ghost" : "lime"}`} style={a.active ? { color: "var(--danger)", marginRight: "auto" } : { marginRight: "auto" }}
                onClick={() => setMode("toggle")}>
          {a.active ? "Switch off" : "Switch on"}
        </button>
        <button className="btn ghost" onClick={() => setMode("password")}><Icon name="key" size={15} /> New password</button>
      </div>
    </Modal>
  );
}

function NewAccount({ onClose, onMade }) {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState(makePassword);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [made, setMade] = useState(null);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await callAdminUsers({ action: "create", name, email, password });
      setMade({ name, email, password });
    } catch (e) {
      setError(e.message);
    }
    setBusy(false);
  }

  if (made) {
    const text = `AutoFare admin\n${location.origin}\nEmail: ${made.email}\nPassword: ${made.password}`;
    return (
      <Modal title="Account created" subtitle={`Send these to ${made.name}. The password is shown only once.`} onClose={() => { onClose(); onMade(); }}>
        <pre className="codebox" style={{ fontSize: 15 }}>{text}</pre>
        <div className="modal-foot"><CopyButton text={text} label="Copy login" className="btn lime" /></div>
      </Modal>
    );
  }

  return (
    <Modal title="New reseller" subtitle="They will only see the codes they make themselves." onClose={onClose}>
      <form onSubmit={submit}>
        <div className="fields">
          <label className="field full">
            Name
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Abu Khaled" required autoFocus />
          </label>
          <label className="field full">
            Email (their login)
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </label>
          <label className="field full">
            Password
            <input value={password} onChange={(e) => setPassword(e.target.value)} minLength={10} required />
          </label>
        </div>
        {error && <p className="error">{error}</p>}
        <div className="modal-foot">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn lime" disabled={busy}>{busy ? "Creating…" : "Create account"}</button>
        </div>
      </form>
    </Modal>
  );
}

async function callAdminUsers(body) {
  const { data } = await supabase.auth.getSession();
  const res = await fetch(`${SUPABASE_URL}/functions/v1/admin-users`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${data.session?.access_token}` },
    body: JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error ?? `failed (${res.status})`);
  return json;
}

function makePassword() {
  // 54 symbols; drop bytes past the last multiple so every symbol is equally likely
  const abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
  let out = "";
  while (out.length < 14) {
    for (const b of crypto.getRandomValues(new Uint8Array(16))) {
      if (b < 216 && out.length < 14) out += abc[b % 54];
    }
  }
  return out;
}
