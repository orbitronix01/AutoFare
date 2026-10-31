import React, { useEffect, useState } from "react";
import { supabase, unwrap, fmtDate, fmtDateTime, daysLeft, prettyCode, isForever } from "../supabase.js";
import { PageHead, Seg, SearchBox, Modal, Avatar, Icon, Empty } from "../ui.jsx";
import { CodeDetail, RenewModal } from "./Codes.jsx";

// the same short form the app shows the driver under "رقم الجهاز"
export const shortId = (id) => id.slice(0, 8).toUpperCase();

function status(d) {
  const left = daysLeft(d.expires_at);
  if (left == null || left <= 0) return "expired";
  if (left <= 7) return "expiring";
  return "active";
}

const FILTERS = [
  ["all", "All"],
  ["active", "Active"],
  ["expiring", "Ending soon"],
  ["expired", "Expired"],
];

export default function Devices({ isOwner, onChanged }) {
  const [devices, setDevices] = useState(null);
  const [codes, setCodes] = useState([]);
  const [filter, setFilter] = useState("all");
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    (async () => {
      try {
        setDevices(unwrap(await supabase.from("devices").select("*").order("last_seen", { ascending: false, nullsFirst: false })));
        setCodes(unwrap(await supabase.from("codes").select("*").or("redeemed_device.not.is.null,for_device.not.is.null")));
      } catch (e) {
        setError(e.message);
      }
    })();
  }, []);

  const byDevice = {};
  // a phone's codes: the ones it entered, plus a renewal waiting for it
  for (const c of codes) (byDevice[c.redeemed_device ?? c.for_device] ??= []).push(c);
  const customerOf = (d) => (byDevice[d.device_id] ?? []).find((c) => c.customer)?.customer;

  const all = devices ?? [];
  const q = search.trim().toLowerCase().replace(/-/g, "");
  const shown = all
    .filter((d) => filter === "all" || status(d) === filter)
    .filter((d) => {
      if (!q) return true;
      if (d.device_id.toLowerCase().includes(q)) return true;
      return (byDevice[d.device_id] ?? []).some((c) => (c.customer ?? "").toLowerCase().includes(q) || c.code.toLowerCase().includes(q));
    });

  const count = (s) => all.filter((d) => status(d) === s).length;
  const online = all.filter((d) => d.last_seen && Date.now() - new Date(d.last_seen) < 86400000).length;

  return (
    <>
      <PageHead
        title="Phone Directory"
        stats={[
          { icon: "phone", value: all.length, label: "Phones that entered a code" },
          { icon: "shield", value: count("active") + count("expiring"), badge: count("expiring") ? `${count("expiring")} ending soon` : null, tone: "clay", label: "Subscribed now" },
          { icon: "pulse", value: online, label: "Online in the last 24 h" },
        ]}
      />

      <div className="toolbar">
        <SearchBox value={search} onChange={setSearch} placeholder="Search phone ID, customer or code" />
        <Seg options={FILTERS} value={filter} onChange={setFilter} />
      </div>

      {error && <p className="error">{error}</p>}
      {!devices ? (
        <p className="muted">Loading…</p>
      ) : shown.length === 0 ? (
        <div className="panel"><Empty>{all.length ? "No phones match." : "No phone has entered a code yet."}</Empty></div>
      ) : (
        <div className="dir">
          {shown.map((d, i) => {
            const st = status(d);
            const left = Math.max(0, daysLeft(d.expires_at) ?? 0);
            const customer = customerOf(d);
            return (
              <div key={d.device_id} className={`dcard ${st === "expired" ? "dim" : ""}`} onClick={() => setOpen(d)}>
                <div className="dcard-top">
                  <Avatar text={customer || shortId(d.device_id)} tone={["sky", "clay", "lime", "stone"][i % 4]} />
                  <StatusPill st={st} />
                </div>
                <div>
                  <div className="dcard-name">{customer ?? "Unnamed driver"}</div>
                  <div className="dcard-sub mono">{shortId(d.device_id)} · v{d.app_version ?? "?"}</div>
                </div>
                <div className="kv">
                  <div><span>Ends</span><b>{fmtDate(d.expires_at)}</b></div>
                  <div><span>Last seen</span><b>{ago(d.last_seen)}</b></div>
                </div>
                <div className="meter">
                  <div className="meter-track">
                    <div className={`meter-fill ${left <= 7 ? "clay" : left <= 31 ? "lime" : "sky"}`} style={{ width: `${Math.min(100, (left / 31) * 100)}%` }} />
                  </div>
                  {isForever(d.expires_at) ? "Forever" : `${left} ${left === 1 ? "day" : "days"}`}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {open && (
        <DeviceDetail d={open} codes={byDevice[open.device_id] ?? []} isOwner={isOwner}
                      onClose={() => setOpen(null)} onChanged={onChanged} />
      )}
    </>
  );
}

function StatusPill({ st }) {
  const map = {
    active: ["lime", "Active"],
    expiring: ["clay", "Ending soon"],
    expired: ["", "Expired"],
  };
  const [tone, label] = map[st];
  return <span className={`pill ${tone}`}>{label}</span>;
}

function DeviceDetail({ d, codes, isOwner, onClose, onChanged }) {
  const [renewing, setRenewing] = useState(false);
  const [code, setCode] = useState(null);
  const latest = [...codes].sort((a, b) => (b.redeemed_at ?? b.created_at).localeCompare(a.redeemed_at ?? a.created_at));

  // the page reloads only once the modal closes, so the new code stays on screen
  if (renewing)
    return <RenewModal phone={d.device_id} customer={latest[0]?.customer} isOwner={isOwner} onClose={() => { onClose(); onChanged(); }} />;
  if (code) return <CodeDetail c={code} isOwner={isOwner} onClose={onClose} onChanged={onChanged} />;

  return (
    <Modal title={`Phone ${shortId(d.device_id)}`} subtitle={isForever(d.expires_at) ? "Forever" : `${status(d) === "expired" ? "Ended" : "Ends"} ${fmtDate(d.expires_at)}`} onClose={onClose} wide>
      <div className="detail-list">
        <div><span>Full ID</span><b className="mono">{d.device_id}</b></div>
        <div><span>First code</span><b>{fmtDateTime(d.first_seen)}</b></div>
        <div><span>Last check-in</span><b>{fmtDateTime(d.last_seen)}</b></div>
        <div><span>App version</span><b>{d.app_version ?? "—"}</b></div>
      </div>
      <h3 style={{ fontSize: 15, margin: "18px 0 6px" }}>Driver's code</h3>
      {codes.length === 0 ? (
        <p className="muted">No code on file. Renew to send them one.</p>
      ) : (
        <div className="rows">
          {latest.map((c) => (
            <div className="row-item" key={c.id} onClick={() => setCode(c)} style={{ cursor: "pointer" }}>
              <Icon name="ticket" />
              <div className="grow">
                <div className="name mono">{prettyCode(c.code)}</div>
                <div className="sub">{c.customer ?? "no customer"}</div>
              </div>
              <div className="sub">{c.redeemed_at ? `entered ${fmtDate(c.redeemed_at)}` : "waiting for the driver"}</div>
              <div className="figure">{c.lifetime ? "Forever" : `+${c.months} mo`}</div>
            </div>
          ))}
        </div>
      )}
      <div className="modal-foot">
        <button className="btn lime" onClick={() => setRenewing(true)}>
          <Icon name="refresh" size={16} /> Renew
        </button>
      </div>
    </Modal>
  );
}

function ago(s) {
  if (!s) return "never";
  const m = Math.round((Date.now() - new Date(s)) / 60000);
  if (m < 2) return "just now";
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h} h ago`;
  return `${Math.round(h / 24)} d ago`;
}
