import { Icon } from './icons';
import { Segmented, TextInput } from './ui';
import { EXAMPLE_STATE, MAX_LEVELS, QUESTION_TYPES, newQuestion, outcomesOf, percent, questionProblem } from '../lib/decisions';

// the name of a question is no identity field: password managers are told to leave it alone
const NOT_A_LOGIN = { autoComplete: 'off', 'data-lpignore': 'true', 'data-1p-ignore': 'true', 'data-bwignore': 'true', 'data-form-type': 'other' };

// one probability, as a bar the eye can compare with the others
function Probability({ label, value, strong }) {
  return (
    <div className={`decision-outcome ${strong ? 'chosen' : ''}`}>
      <span className="truncate">{label}</span>
      <div className="progress">
        <i style={{ width: `${Math.round(Math.min(1, Math.max(0, value)) * 100)}%` }} />
      </div>
      <span className="num mono">{percent(value)}</span>
    </div>
  );
}

function Answer({ name, answer }) {
  const outcomes = outcomesOf(answer);
  const peak = outcomes.reduce((best, o) => (o.probability > best ? o.probability : best), 0);
  // a yes/no answer is one probability, the one of yes
  const yes = Math.min(1, Math.max(0, Number(answer.noul) || 0));
  return (
    <div className="decision-answer">
      <div className="row between">
        <b className="truncate">{name}</b>
        {answer.confidence !== undefined && (
          <span className="faint small" title="How far the answer stands out from the others: 0% is an even split, 100% a certainty">
            {percent(answer.confidence)} confident
          </span>
        )}
      </div>
      {answer.type === 'noul' && (
        <>
          <Probability label="Yes" value={yes} strong={yes >= 0.5} />
          <Probability label="No" value={1 - yes} strong={yes < 0.5} />
        </>
      )}
      {answer.type === 'choice' && outcomes.map((o) => <Probability key={o.id} label={o.label} value={o.probability} strong={o.chosen} />)}
      {answer.type === 'score' && (
        <>
          <div className="small">
            <span className="mono">{Number(answer.score).toFixed(2)}</span>
            <span className="faint"> on a scale of 0 to {Math.max(0, outcomes.length - 1)}</span>
          </div>
          {outcomes.map((o) => (
            <Probability key={o.id} label={`${o.id} · ${o.label}`} value={o.probability} strong={o.probability === peak} />
          ))}
        </>
      )}
      {!['noul', 'choice', 'score'].includes(answer.type) && <pre className="small">{JSON.stringify(answer, null, 2)}</pre>}
    </div>
  );
}

/** What a decision model answered: every question with the probability of each of its outcomes. */
export function Decisions({ answers }) {
  const names = Object.keys(answers || {});
  if (names.length === 0) return <p className="muted">The model answered no question.</p>;
  return (
    <div className="stack tight">
      {names.map((name) => (
        <Answer key={name} name={name} answer={answers[name] || {}} />
      ))}
    </div>
  );
}

function Question({ question, all, onChange, onRemove, disabled }) {
  const set = (patch) => onChange({ ...question, ...patch });
  const problem = questionProblem(question, all);
  const setOption = (idx, patch) => set({ options: question.options.map((o, i) => (i === idx ? { ...o, ...patch } : o)) });
  const setLevel = (idx, value) => set({ levels: question.levels.map((l, i) => (i === idx ? value : l)) });
  return (
    <div className="decision-question">
      <div className="row">
        <TextInput className="mono name" value={question.name} onChange={(v) => set({ name: v.replace(/\s+/g, '_') })} placeholder="question" disabled={disabled} {...NOT_A_LOGIN} />
        <Segmented value={question.type} onChange={(type) => !disabled && set({ type })} options={QUESTION_TYPES} />
        <button className="btn sm ghost icon" title="Remove this question" disabled={disabled || all.length < 2} onClick={onRemove}>
          <Icon name="trash" />
        </button>
      </div>
      <TextInput value={question.instructions} onChange={(v) => set({ instructions: v })} placeholder="The question to ask about the state" disabled={disabled} />
      {question.type === 'choice' && (
        <div className="stack tight">
          {question.options.map((option, idx) => (
            <div key={idx} className="row">
              <TextInput className="mono name" value={option.name} onChange={(v) => setOption(idx, { name: v })} placeholder="option" disabled={disabled} {...NOT_A_LOGIN} />
              <TextInput value={option.description} onChange={(v) => setOption(idx, { description: v })} placeholder="What this option means" disabled={disabled} />
              <button className="btn sm ghost icon" title="Remove this option" disabled={disabled || question.options.length < 3} onClick={() => set({ options: question.options.filter((_, i) => i !== idx) })}>
                <Icon name="x" />
              </button>
            </div>
          ))}
          <button className="btn sm ghost" disabled={disabled} onClick={() => set({ options: [...question.options, { name: '', description: '' }] })}>
            <Icon name="plus" />
            Add an option
          </button>
        </div>
      )}
      {question.type === 'score' && (
        <div className="stack tight">
          {question.levels.map((level, idx) => (
            <div key={idx} className="row">
              <span className="faint mono level">{idx}</span>
              <TextInput value={level} onChange={(v) => setLevel(idx, v)} placeholder={idx === 0 ? 'The lowest level' : 'The next level up'} disabled={disabled} />
              <button className="btn sm ghost icon" title="Remove this level" disabled={disabled || question.levels.length < 3} onClick={() => set({ levels: question.levels.filter((_, i) => i !== idx) })}>
                <Icon name="x" />
              </button>
            </div>
          ))}
          <button className="btn sm ghost" disabled={disabled || question.levels.length >= MAX_LEVELS} onClick={() => set({ levels: [...question.levels, ''] })}>
            <Icon name="plus" />
            Add a level
          </button>
        </div>
      )}
      {problem && <div className="faint small">{problem}</div>}
    </div>
  );
}

/**
 * The form of a decision: the state the model looks at, and the questions it answers about it. A decision
 * model writes nothing: each answer is a probability for every possible outcome.
 */
export function DecisionForm({ state, onState, questions, onQuestions, disabled }) {
  return (
    <div className="stack tight">
      <textarea rows={3} placeholder={EXAMPLE_STATE} value={state} onChange={(e) => onState(e.target.value)} disabled={disabled} />
      <span className="faint small">The state the questions are about: a text, or a JSON object or array.</span>
      {questions.map((question, idx) => (
        <Question
          key={idx}
          question={question}
          all={questions}
          disabled={disabled}
          onChange={(next) => onQuestions(questions.map((q, i) => (i === idx ? next : q)))}
          onRemove={() => onQuestions(questions.filter((_, i) => i !== idx))}
        />
      ))}
      <button className="btn sm" style={{ alignSelf: 'flex-start' }} disabled={disabled} onClick={() => onQuestions([...questions, newQuestion('noul', `question_${questions.length + 1}`)])}>
        <Icon name="plus" />
        Add a question
      </button>
    </div>
  );
}
