import { attempted, fmtSpeed, fmtSuccess, HEALTH, healthNumber, statusOf } from '../lib/health';
import { fmtDate, fmtInt, fmtMs, fmtNumber, fmtRelative } from '../lib/format';
import { Link } from '../lib/router';

const labelOf = (status) => HEALTH.find((h) => h.value === status);

export function HealthDot({ health }) {
  const status = statusOf(health);
  return <span className={`dot health-${status}`} title={labelOf(status).label} />;
}

// one line: success rate, median latency, speed and volume
export function HealthSummary({ health, calls = true }) {
  if (!health || attempted(health) <= 0) return <span className="faint">No calls</span>;
  const p50 = healthNumber(health.p50_ms);
  const speed = healthNumber(health.tokens_per_second);
  const title = [
    `${fmtInt(health.calls)} calls, ${fmtInt(health.failures)} failed`,
    health.last_failure ? `Last failure ${fmtRelative(health.last_failure)}: ${health.last_failure_message || 'unknown error'}` : null,
  ]
    .filter(Boolean)
    .join('\n');
  return (
    <span className="health-summary" title={title}>
      <HealthDot health={health} />
      <span>{fmtSuccess(health)} ok</span>
      {p50 !== null && <span>p50 {fmtMs(p50)}</span>}
      {speed !== null && <span>{fmtSpeed(speed)}</span>}
      {calls && <span className="faint">{fmtNumber(health.calls)} calls</span>}
    </span>
  );
}

function Row({ label, children }) {
  return (
    <>
      <div className="muted">{label}</div>
      <div>{children}</div>
    </>
  );
}

// everything the calls of the period say about a model
export function HealthDetails({ health, periodLabel, logsUrl }) {
  const status = statusOf(health);
  return (
    <div className="details-section">
      <h3>Health · {periodLabel}</h3>
      {status === 'idle' ? (
        <p className="muted">No call served in this period.</p>
      ) : (
        <div className="kv">
          <Row label="Status">
            <span className="health-summary">
              <HealthDot health={health} />
              {labelOf(status).label}, {fmtSuccess(health)} of the calls succeeded
            </span>
          </Row>
          <Row label="Calls">
            {fmtInt(health.calls)}
            {healthNumber(health.cached) > 0 && <span className="faint"> · {fmtInt(health.cached)} from the cache</span>}
            {healthNumber(health.refusals) > 0 && <span className="faint"> · {fmtInt(health.refusals)} refused by the gateway</span>}
          </Row>
          <Row label="Failures">{fmtInt(health.failures)}</Row>
          <Row label="Latency">{healthNumber(health.p50_ms) === null ? '—' : `p50 ${fmtMs(health.p50_ms)} · p95 ${fmtMs(health.p95_ms)}`}</Row>
          {healthNumber(health.p50_ttft_ms) !== null && <Row label="First token">p50 {fmtMs(health.p50_ttft_ms)}</Row>}
          <Row label="Speed">{fmtSpeed(health.tokens_per_second)}</Row>
          <Row label="Last call">{fmtDate(health.last_call)}</Row>
          {health.last_failure && (
            <Row label="Last failure">
              {fmtDate(health.last_failure)}
              {health.last_failure_message && <div className="muted small">{health.last_failure_message}</div>}
            </Row>
          )}
        </div>
      )}
      {logsUrl && status !== 'idle' && (
        <p style={{ margin: '10px 0 0' }}>
          <Link className="link small" to={logsUrl}>
            See the calls of this model
          </Link>
        </p>
      )}
    </div>
  );
}
