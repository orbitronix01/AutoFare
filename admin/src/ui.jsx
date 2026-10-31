import React, { useEffect, useState } from "react";

/* ─────────────── icons: 24-grid strokes, drawn in currentColor ─────────────── */

const PATHS = {
  search: "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.3-4.3",
  plus: "M12 5v14M5 12h14",
  sun: "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4",
  moon: "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z",
  logout: "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9",
  key: "M21 2l-2 2m-7.6 7.6a5.5 5.5 0 1 1-7.8 7.8 5.5 5.5 0 0 1 7.8-7.8zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4",
  phone: "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zM11 18h2",
  users: "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM23 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8",
  chart: "M3 3v18h18M7 15l4-4 3 3 6-6",
  pulse: "M22 12h-4l-3 9L9 3l-3 9H2",
  clock: "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2",
  copy: "M20 9h-9a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2zM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1",
  check: "M20 6L9 17l-5-5",
  x: "M18 6L6 18M6 6l12 12",
  shield: "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z",
  cash: "M2 6h20v12H2zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 12h.01M18 12h.01",
  trash: "M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v6M14 11v6",
  arrow: "M7 17L17 7M7 7h10v10",
  ticket: "M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v3a2 2 0 0 0 0 4v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 0 0-4V7zM13 5v2M13 17v2M13 11v2",
  refresh: "M23 4v6h-6M1 20v-6h6M3.5 9a9 9 0 0 1 14.9-3.4L23 10M1 14l4.6 4.4A9 9 0 0 0 20.5 15",
  whatsapp: "M21 11.5a8.4 8.4 0 0 1-12.4 7.4L3 21l2.1-5.5A8.4 8.4 0 1 1 21 11.5z",
};

export function Icon({ name, size = 18, stroke = 1.8 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={PATHS[name]} />
    </svg>
  );
}

/* ─────────────── building blocks ─────────────── */

export function PageHead({ title, stats = [], back }) {
  const [first, ...rest] = title.split(" ");
  return (
    <div className="pagehead">
      {back}
      <h1>
        {first}
        <br />
        {rest.join(" ")}
      </h1>
      <div className="statchips">
        {stats.map((s) => (
          <div className="statchip" key={s.label}>
            <span className="statchip-icon"><Icon name={s.icon} /></span>
            <div>
              <div className="statchip-value">
                {s.value}
                {s.badge && <span className={`badge ${s.tone ?? "lime"}`}>{s.badge}</span>}
              </div>
              <div className="statchip-label">{s.label}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function Panel({ title, actions, children, className = "" }) {
  return (
    <section className={`panel ${className}`}>
      {(title || actions) && (
        <div className="panel-head">
          <h2>{title}</h2>
          <div className="panel-actions">{actions}</div>
        </div>
      )}
      {children}
    </section>
  );
}

export function RoundButton({ icon, onClick, title, tone }) {
  return (
    <button className={`round ${tone ?? ""}`} onClick={onClick} title={title} aria-label={title}>
      <Icon name={icon} size={16} />
    </button>
  );
}

export function Seg({ options, value, onChange }) {
  return (
    <div className="seg">
      {options.map((o) => {
        const [v, label] = Array.isArray(o) ? o : [o, o];
        return (
          <button key={v} className={v === value ? "on" : ""} onClick={() => onChange(v)}>{label}</button>
        );
      })}
    </div>
  );
}

export function SearchBox({ value, onChange, placeholder }) {
  return (
    <label className="search">
      <Icon name="search" size={16} />
      <input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  );
}

export function Avatar({ text, tone = "sky", size = 44 }) {
  const initials = (text || "?")
    .replace(/[^\p{L}\p{N} ]/gu, "")
    .split(" ")
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0])
    .join("")
    .toUpperCase() || "?";
  return (
    <span className={`avatar ${tone}`} style={{ width: size, height: size, fontSize: size * 0.32 }}>{initials}</span>
  );
}

export function Modal({ title, subtitle, onClose, children, wide }) {
  useEffect(() => {
    const onKey = (e) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? "wide" : ""}`} role="dialog" aria-modal="true">
        <div className="modal-head">
          <div>
            <h3>{title}</h3>
            {subtitle && <p>{subtitle}</p>}
          </div>
          <RoundButton icon="x" onClick={onClose} title="Close" />
        </div>
        {children}
      </div>
    </div>
  );
}

/** A yes/no question in a modal, instead of the browser's confirm(). */
export function Confirm({ title, body, yes, danger, onYes, onClose, input }) {
  const [text, setText] = useState(input?.value ?? "");
  const [busy, setBusy] = useState(false);
  return (
    <Modal title={title} subtitle={body} onClose={onClose}>
      {input && (
        <label className="field">
          {input.label}
          <input value={text} onChange={(e) => setText(e.target.value)} placeholder={input.placeholder} autoFocus />
        </label>
      )}
      <div className="modal-foot">
        <button className="btn ghost" onClick={onClose}>Cancel</button>
        <button
          className={`btn ${danger ? "danger" : "lime"}`}
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            // onYes returns true to stay open (it has something else to show)
            const stay = await onYes(text);
            setBusy(false);
            if (stay !== true) onClose();
          }}
        >
          {busy ? "…" : yes}
        </button>
      </div>
    </Modal>
  );
}

export function CopyButton({ text, label = "Copy", icon = "copy", className = "btn ghost" }) {
  const [done, setDone] = useState(false);
  return (
    <button
      className={className}
      onClick={(e) => {
        e.stopPropagation();
        navigator.clipboard.writeText(text);
        setDone(true);
        setTimeout(() => setDone(false), 1200);
      }}
    >
      <Icon name={done ? "check" : icon} size={15} />
      {label !== "" && (done ? " Copied" : ` ${label}`)}
    </button>
  );
}

export function Empty({ children }) {
  return <div className="empty">{children}</div>;
}

/* ─────────────── chart ─────────────── */

/** Smooth area line through the points, plus an optional dashed comparison line. */
export function AreaChart({ labels, values, dashed, height = 220 }) {
  const W = 700;
  const H = height;
  const pad = { l: 40, r: 10, t: 14, b: 26 };
  const max = Math.max(1, ...values, ...(dashed ?? []));
  const top = niceCeil(max);
  const x = (i) => pad.l + (i * (W - pad.l - pad.r)) / Math.max(1, values.length - 1);
  const y = (v) => pad.t + (1 - v / top) * (H - pad.t - pad.b);

  const line = smooth(values.map((v, i) => [x(i), y(v)]));
  const area = `${line} L${x(values.length - 1)},${y(0)} L${x(0)},${y(0)} Z`;
  const dash = dashed ? smooth(dashed.map((v, i) => [x(i), y(v)])) : null;

  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img">
      <defs>
        <linearGradient id="areaFill" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor="var(--chart)" stopOpacity="0.35" />
          <stop offset="100%" stopColor="var(--chart)" stopOpacity="0" />
        </linearGradient>
      </defs>
      {[0, 0.5, 1].map((f) => (
        <g key={f}>
          <line x1={pad.l} x2={W - pad.r} y1={y(top * f)} y2={y(top * f)} className="grid" />
          <text x={pad.l - 8} y={y(top * f) + 4} textAnchor="end" className="axis">{Math.round(top * f)}</text>
        </g>
      ))}
      {dash && <path d={dash} className="dash" />}
      <path d={area} fill="url(#areaFill)" />
      <path d={line} className="line" />
      {values.map((v, i) => (
        <circle key={i} cx={x(i)} cy={y(v)} r="3.5" className="dot"><title>{v}</title></circle>
      ))}
      {labels.map((l, i) => (
        <text key={l + i} x={x(i)} y={H - 6} textAnchor="middle" className="axis">{l}</text>
      ))}
    </svg>
  );
}

function niceCeil(v) {
  if (v <= 5) return 5;
  const p = 10 ** Math.floor(Math.log10(v));
  return Math.ceil(v / p) * p;
}

function smooth(pts) {
  if (pts.length < 2) return pts.length ? `M${pts[0][0]},${pts[0][1]}` : "";
  let d = `M${pts[0][0]},${pts[0][1]}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const [x0, y0] = pts[i - 1] ?? pts[i];
    const [x1, y1] = pts[i];
    const [x2, y2] = pts[i + 1];
    const [x3, y3] = pts[i + 2] ?? pts[i + 1];
    const c1 = [x1 + (x2 - x0) / 6, y1 + (y2 - y0) / 6];
    const c2 = [x2 - (x3 - x1) / 6, y2 - (y3 - y1) / 6];
    d += ` C${c1[0]},${c1[1]} ${c2[0]},${c2[1]} ${x2},${y2}`;
  }
  return d;
}
