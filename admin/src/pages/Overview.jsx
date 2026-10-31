import React, { useEffect, useState } from "react";
import { supabase, unwrap, priceOf } from "../supabase.js";
import { PageHead, Panel, RoundButton, AreaChart, Avatar, Empty } from "../ui.jsx";
import { CodeCard } from "./Codes.jsx";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export default function Overview({ isOwner, goTo, onNewCodes }) {
  const [s, setS] = useState(null);
  const [recent, setRecent] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    (async () => {
      try {
        setS(unwrap(await supabase.rpc("admin_stats")));
        setRecent(unwrap(await supabase.from("codes").select("*").order("created_at", { ascending: false }).limit(4)));
      } catch (e) {
        setError(e.message);
      }
    })();
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!s) return <p className="muted">Loading…</p>;

  const thisMonth = s.by_month[s.by_month.length - 1] ?? { activated: 0, renewed: 0, created: 0 };
  const used = s.by_month.map((m) => m.activated + m.renewed);
  const activeShare = s.devices_total ? Math.round((s.devices_active / s.devices_total) * 100) : 0;
  const mixTotal = Math.max(1, s.codes_created);
  // deleted codes that were used still sit in codes_redeemed
  const unusedDeleted = s.codes_created - s.codes_redeemed - s.codes_unused;

  return (
    <>
      <PageHead
        title={isOwner ? "Subscriptions Overview" : "Your Overview"}
        stats={[
          { icon: "shield", value: s.devices_active, badge: thisMonth.activated ? `+${thisMonth.activated} new this month` : null, label: "Active subscriptions" },
          { icon: "pulse", value: s.devices_online, label: "Phones online, last 24 h" },
          { icon: "chart", value: s.months_sold, badge: `${s.renewals} renewals`, tone: "sky", label: "Months sold" },
          ...(isOwner ? [{ icon: "cash", value: priceOf(s.months_sold), label: "Earned from codes entered" }] : []),
        ]}
      />

      <div className="grid g-main">
        <Panel title="Activations & renewals" actions={<RoundButton icon="arrow" title="All codes" onClick={() => goTo("codes")} />}>
          <div>
            <span className="big">{s.activations + s.renewals}</span>
            <span className="big-sub">
              codes entered · <b>{s.activations}</b> new drivers · <b>{s.renewals}</b> renewals
            </span>
          </div>
          <div className="chart-legend">
            <span><i /> entered on a phone</span>
            <span><i className="dashed" /> codes made</span>
          </div>
          <AreaChart
            labels={s.by_month.map((m) => MONTHS[Number(m.month.slice(5)) - 1])}
            values={used}
            dashed={s.by_month.map((m) => m.created)}
          />
        </Panel>

        <Panel
          title="Latest codes"
          actions={
            <>
              <RoundButton icon="plus" title="New codes" onClick={onNewCodes} />
              <RoundButton icon="arrow" title="All codes" onClick={() => goTo("codes")} />
            </>
          }
        >
          {recent.length === 0 ? (
            <Empty>No codes yet. Make the first one with the + button.</Empty>
          ) : (
            <div className="recent">
              {recent.map((c) => <CodeCard key={c.id} c={c} showPrice={isOwner} compact />)}
            </div>
          )}
        </Panel>
      </div>

      <div className="grid g-3" style={{ marginTop: 18 }}>
        <Panel title="Still subscribed">
          <div className="ring">
            <svg width="150" height="150" viewBox="0 0 150 150">
              <circle cx="75" cy="75" r="62" fill="none" strokeWidth="12" className="track" />
              <circle cx="75" cy="75" r="62" fill="none" strokeWidth="12" strokeLinecap="round" className="fill"
                      strokeDasharray={2 * Math.PI * 62} strokeDashoffset={2 * Math.PI * 62 * (1 - activeShare / 100)} />
            </svg>
            <div className="ring-label"><div><b>{activeShare}%</b><span>{s.devices_active} of {s.devices_total} phones</span></div></div>
          </div>
        </Panel>

        <Panel title="Code mix">
          <span className="big" style={{ fontSize: 34 }}>{s.codes_created}</span>
          <span className="big-sub">codes made</span>
          <div className="mix">
            <div className="olive" style={{ flex: s.codes_redeemed / mixTotal }} title="used" />
            <div className="sky" style={{ flex: s.codes_unused / mixTotal }} title="waiting" />
            <div className="stone" style={{ flex: unusedDeleted / mixTotal }} title="deleted before use" />
          </div>
          <div className="legend">
            <span><i className="dot olive" />{s.codes_redeemed} used</span>
            <span><i className="dot sky" />{s.codes_unused} waiting</span>
            {unusedDeleted > 0 && <span><i className="dot stone" />{unusedDeleted} deleted unused</span>}
          </div>
        </Panel>

        <Panel title={isOwner ? "By account" : "Your account"} actions={isOwner && <RoundButton icon="arrow" title="Team" onClick={() => goTo("accounts")} />}>
          <div className="rows">
            {s.by_reseller.map((r, i) => (
              <div className="row-item" key={r.user_id} style={{ opacity: r.active ? 1 : 0.5 }}>
                <Avatar text={r.name} tone={["lime", "sky", "clay", "stone"][i % 4]} size={36} />
                <div className="grow">
                  <div className="name">{r.name}</div>
                  <div className="sub">{r.redeemed} of {r.created} codes used</div>
                </div>
                <div className="figure">{isOwner ? priceOf(r.months) : `${r.months} mo`}</div>
              </div>
            ))}
          </div>
        </Panel>
      </div>
    </>
  );
}
