import React, { useEffect, useState } from "react";
import { supabase, unwrap, prettyCode, fmtDate, fmtDateTime, priceOf, codePrice, codeLength, PRICE_JD } from "../supabase.js";
import { PageHead, Seg, SearchBox, Icon, Modal, Confirm, CopyButton, Avatar, Empty } from "../ui.jsx";

const COLUMNS = [
  { id: "waiting", label: "Waiting", tone: "sky", test: (c) => !c.redeemed_at },
  { id: "used", label: "Used", tone: "olive", test: (c) => !!c.redeemed_at },
];

// deleted codes never reach the page: the codes_read policy hides them
export const toneOf = (c) => (c.redeemed_at ? "olive" : "sky");

export default function Codes({ isOwner, onNewCodes, onChanged }) {
  const [codes, setCodes] = useState(null);
  const [admins, setAdmins] = useState([]);
  const [by, setBy] = useState("all");
  const [view, setView] = useState("board");
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    (async () => {
      try {
        setCodes(unwrap(await supabase.from("codes").select("*").order("created_at", { ascending: false }).limit(2000)));
        if (isOwner) setAdmins(unwrap(await supabase.from("admins").select("user_id, name").order("created_at")));
      } catch (e) {
        setError(e.message);
      }
    })();
  }, []);

  const names = Object.fromEntries(admins.map((a) => [a.user_id, a.name]));
  const q = search.trim().toUpperCase().replace(/-/g, "");
  const shown = (codes ?? [])
    .filter((c) => by === "all" || c.created_by === by)
    .filter((c) => !q || c.code.includes(q) || (c.customer ?? "").toUpperCase().includes(q));

  const all = codes ?? [];
  const waiting = all.filter(COLUMNS[0].test).length;
  const thisMonth = all.filter((c) => c.redeemed_at && c.redeemed_at.slice(0, 7) === new Date().toISOString().slice(0, 7)).length;
  const waitingJd = all.filter(COLUMNS[0].test).reduce((n, c) => n + (c.lifetime ? 0 : c.months * PRICE_JD), 0);

  return (
    <>
      <PageHead
        title="Code Pipeline"
        stats={[
          { icon: "ticket", value: all.length, label: "Codes made" },
          { icon: "clock", value: waiting, badge: waiting ? "not entered yet" : null, tone: "sky", label: "Waiting for a driver" },
          { icon: "check", value: thisMonth, badge: "this month", label: "Entered on a phone" },
          ...(isOwner ? [{ icon: "cash", value: `${waitingJd} JD`, tone: "sky", label: "In codes not entered yet" }] : []),
        ]}
      />

      <div className="toolbar">
        <SearchBox value={search} onChange={setSearch} placeholder="Search code or customer" />
        {isOwner && admins.length > 1 && (
          <div className="chips">
            <button className={`chip plain ${by === "all" ? "on" : ""}`} onClick={() => setBy("all")}>Everyone</button>
            {admins.map((a, i) => (
              <button key={a.user_id} className={`chip ${by === a.user_id ? "on" : ""}`} onClick={() => setBy(a.user_id)}>
                <Avatar text={a.name} size={24} tone={["lime", "sky", "clay", "stone"][i % 4]} /> {a.name}
              </button>
            ))}
          </div>
        )}
        <span className="spacer" />
        <Seg options={[["board", "Board"], ["list", "List"]]} value={view} onChange={setView} />
        <button className="btn lime" onClick={onNewCodes}><Icon name="plus" size={16} /> New codes</button>
      </div>

      {error && <p className="error">{error}</p>}
      {!codes ? (
        <p className="muted">Loading…</p>
      ) : view === "board" ? (
        <div className="board">
          {COLUMNS.map((col) => {
            const items = shown.filter(col.test);
            return (
              <div className="column" key={col.id}>
                <div className="column-head">
                  <span className={`dot ${col.tone}`} />
                  {col.label}
                  <span className="count">{items.length}</span>
                  <span className="spacer" />
                  {col.id === "waiting" && (
                    <button className="round" title="New codes" onClick={onNewCodes}><Icon name="plus" size={15} /></button>
                  )}
                </div>
                {items.length === 0 ? (
                  <Empty>Nothing here.</Empty>
                ) : (
                  items.slice(0, 60).map((c) => (
                    <CodeCard key={c.id} c={c} maker={isOwner ? names[c.created_by] : null} showPrice={isOwner} onOpen={() => setOpen(c)} />
                  ))
                )}
                {items.length > 60 && <p className="muted" style={{ textAlign: "center" }}>+{items.length - 60} more — use search</p>}
              </div>
            );
          })}
        </div>
      ) : (
        <div className="panel">
          {shown.length === 0 ? (
            <Empty>No codes match.</Empty>
          ) : (
            <div className="rows">
              {shown.map((c) => (
                <div className="row-item" key={c.id} onClick={() => setOpen(c)} style={{ cursor: "pointer" }}>
                  <span className={`dot ${toneOf(c)}`} style={{ width: 10, height: 10, borderRadius: "50%" }} />
                  <div className="grow">
                    <div className="name mono">{prettyCode(c.code)}</div>
                    <div className="sub">
                      {c.customer ?? "no customer"} · {codeLength(c)}{isOwner ? ` · ${codePrice(c)}` : ""}{isOwner && names[c.created_by] ? ` · by ${names[c.created_by]}` : ""}
                    </div>
                  </div>
                  <div className="sub">{statusText(c)}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {open && <CodeDetail c={open} maker={names[open.created_by]} isOwner={isOwner} onClose={() => setOpen(null)} onChanged={onChanged} />}
    </>
  );
}

function statusText(c) {
  if (c.redeemed_at) return `used ${fmtDate(c.redeemed_at)}`;
  if (c.for_device) return `renewal, not entered yet`;
  return `made ${fmtDate(c.created_at)}`;
}

export function CodeCard({ c, maker, showPrice, onOpen, compact }) {
  return (
    <div className={`tcard ${toneOf(c)}`} onClick={onOpen} style={{ cursor: onOpen ? "pointer" : "default", minHeight: compact ? 118 : 132 }}>
      <div>
        <div className="tcard-date">{c.redeemed_at ? `Used ${fmtDate(c.redeemed_at)}` : `Made ${fmtDate(c.created_at)}`}</div>
        <div className="tcard-code">{prettyCode(c.code)}</div>
        <div className="tcard-sub">{c.customer ?? "No customer"}{maker ? ` · ${maker}` : ""}</div>
      </div>
      <div className="tcard-foot">
        <div className="tcard-big">
          {c.lifetime ? "∞" : c.months}
          <small>{c.lifetime ? "forever" : c.months === 1 ? "month" : "months"}{showPrice ? ` · ${codePrice(c)}` : ""}</small>
        </div>
        {!c.redeemed_at && !compact && (
          <CopyButton text={prettyCode(c.code)} label="" className="more" />
        )}
      </div>
    </div>
  );
}

/** One code. Also opened from a phone's details, so it stands on its own. */
export function CodeDetail({ c, maker, isOwner, onClose, onChanged }) {
  const [confirming, setConfirming] = useState(false);
  const [renewing, setRenewing] = useState(false);
  const [error, setError] = useState(null);
  const msg = whatsapp(c);

  // the page reloads only once the modal closes, so the new code stays on screen
  if (renewing)
    return <RenewModal phone={c.redeemed_device} customer={c.customer} isOwner={isOwner} onClose={() => { onClose(); onChanged(); }} />;

  if (confirming)
    return (
      <Confirm
        title="Delete this code?"
        body={c.redeemed_at
          ? `${prettyCode(c.code)} disappears from your lists. The phone keeps the time it already got, and the months still count in your totals.`
          : `${prettyCode(c.code)} disappears and can never be entered.`}
        yes="Delete code"
        danger
        onClose={onClose}
        onYes={async () => {
          const { error } = await supabase.rpc("delete_code", { p_id: c.id });
          if (!error) return onChanged();
          setError(error.message);
          setConfirming(false);
          return true;
        }}
      />
    );

  return (
    <Modal title={prettyCode(c.code)} subtitle={`${codeLength(c)} · ${statusText(c)}`} onClose={onClose}>
      <div className="detail-list">
        <div><span>Customer</span><b>{c.customer ?? "—"}</b></div>
        {maker && <div><span>Made by</span><b>{maker}</b></div>}
        <div><span>Made</span><b>{fmtDateTime(c.created_at)}</b></div>
        <div><span>Entered</span><b>{c.redeemed_at ? fmtDateTime(c.redeemed_at) : "not yet"}</b></div>
        {(c.redeemed_device ?? c.for_device) && (
          <div><span>{c.redeemed_device ? "Phone" : "Only for phone"}</span><b className="mono">{(c.redeemed_device ?? c.for_device).slice(0, 8).toUpperCase()}</b></div>
        )}
        {isOwner && <div><span>Price</span><b>{codePrice(c)}</b></div>}
      </div>
      {error && <p className="error">{error}</p>}
      <div className="modal-foot">
        <button className="btn ghost" style={{ color: "var(--danger)", marginRight: "auto" }} onClick={() => setConfirming(true)}>
          <Icon name="trash" size={15} /> Delete
        </button>
        {!c.redeemed_at && (
          <>
            <CopyButton text={msg} label="WhatsApp message" icon="whatsapp" />
            <CopyButton text={prettyCode(c.code)} label="Copy code" className="btn lime" />
          </>
        )}
        {c.redeemed_device && (
          <button className="btn lime" onClick={() => setRenewing(true)}>
            <Icon name="refresh" size={16} /> Renew this driver
          </button>
        )}
      </div>
    </Modal>
  );
}

const whatsapp = (c) =>
  `رمز اشتراك AutoFare (${c.lifetime ? "مدى الحياة" : `${c.months} ${c.months === 1 ? "شهر" : "أشهر"}`}):\n${prettyCode(c.code)}\n\nافتح التطبيق واكتب الرمز.`;

/**
 * A renewal: one code that only this phone can enter. The driver's older code
 * is deleted (a driver holds one code at a time); the phone keeps its time.
 */
export function RenewModal({ phone, customer, isOwner, onClose }) {
  return <NewCodesModal phone={phone} customer={customer ?? ""} isOwner={isOwner} onMade={() => {}} onClose={onClose} />;
}

/** Make codes. Lives here, opened from anywhere: the rail, the board, the overview. */
export function NewCodesModal({ onClose, onMade, phone, customer: forCustomer, isOwner }) {
  const [months, setMonths] = useState(1);
  const [count, setCount] = useState(1);
  const [customer, setCustomer] = useState(forCustomer ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [made, setMade] = useState(null);

  const forever = months === "forever";

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const rows = unwrap(
        await supabase.rpc("create_codes", {
          p_months: forever ? 1 : Number(months), p_count: phone ? 1 : Number(count), p_customer: customer || null,
          p_device: phone ?? null, p_lifetime: forever,
        }),
      );
      setMade(rows);
      onMade();
    } catch (e) {
      setError(e.message);
    }
    setBusy(false);
  }

  if (made) {
    const text = made.map((c) => prettyCode(c.code)).join("\n");
    return (
      <Modal title={made.length === 1 ? "Code ready" : `${made.length} codes ready`} subtitle="Send to the driver. Each code works once." onClose={onClose}>
        <pre className="codebox">{text}</pre>
        <div className="modal-foot">
          {made.length === 1 && <CopyButton text={whatsapp(made[0])} label="WhatsApp message" icon="whatsapp" />}
          <CopyButton text={text} label={made.length === 1 ? "Copy code" : "Copy all"} className="btn lime" />
        </div>
      </Modal>
    );
  }

  return (
    <Modal title={phone ? "Renew driver" : "New codes"}
           subtitle={phone
             ? `A new code that only phone ${phone.slice(0, 8).toUpperCase()} can enter. Their old code is deleted; the months go on top of any time left.`
             : "Each code adds its months to whichever phone enters it."}
           onClose={onClose}>
      <form onSubmit={submit}>
        <div className="fields">
          <label className="field">
            Length
            <select value={months} onChange={(e) => setMonths(e.target.value)}>
              {[1, 2, 3, 6, 12].map((m) => (
                <option key={m} value={m}>{m} {m === 1 ? "month" : "months"}{isOwner ? ` — ${priceOf(m)}` : ""}</option>
              ))}
              {/* the owner's and resellers' own phones; the database refuses it from anyone else */}
              {isOwner && <option value="forever">Forever — free, for your team's own phones</option>}
            </select>
          </label>
          {!phone && (
            <label className="field">
              How many
              <input type="number" min="1" max="50" value={count} onChange={(e) => setCount(e.target.value)} />
            </label>
          )}
          <label className="field full">
            Customer (optional)
            <input placeholder="Name or phone — e.g. Abu Ahmad 0791234567" value={customer} onChange={(e) => setCustomer(e.target.value)} autoFocus />
          </label>
        </div>
        {error && <p className="error">{error}</p>}
        <div className="modal-foot">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn lime" disabled={busy}>{busy ? "Making…" : phone ? "Make renewal code" : count > 1 ? `Make ${count} codes` : "Make code"}</button>
        </div>
      </form>
    </Modal>
  );
}
