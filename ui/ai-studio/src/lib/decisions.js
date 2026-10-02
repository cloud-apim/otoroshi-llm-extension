// The questions of a decision model, as the playground edits them and as the System One api takes them:
// `{ <name>: { type, instructions, criteria } }`, the criteria being the options of a choice (a map) or the
// levels of a score (an ordered list). A yes/no question (`noul`) needs none.

export const QUESTION_TYPES = [
  { value: 'noul', label: 'Yes / no' },
  { value: 'choice', label: 'Choice' },
  { value: 'score', label: 'Score' },
];

export const MAX_LEVELS = 10;

// a question in the form: `options` is what a choice uses, `levels` what a score uses
export const newQuestion = (type = 'noul', name = '') => ({
  name,
  type,
  instructions: '',
  options: [
    { name: '', description: '' },
    { name: '', description: '' },
  ],
  levels: ['', ''],
});

export const EXAMPLE_STATE = 'The checkout has been failing for every customer for the last hour.';

export const exampleQuestions = () => [
  { ...newQuestion('noul', 'urgent'), instructions: 'Is this support request urgent?' },
  {
    ...newQuestion('choice', 'team'),
    instructions: 'Which team should handle this request?',
    options: [
      { name: 'billing', description: 'Invoices and payments' },
      { name: 'technical', description: 'Outages, bugs and integrations' },
      { name: 'sales', description: 'Contracts and pricing' },
    ],
  },
  {
    ...newQuestion('score', 'severity'),
    instructions: 'How severe is the customer impact?',
    levels: ['Minor', 'Degraded, with a workaround', 'Blocking'],
  },
];

// a state written as json is sent as json (an object or an array), anything else as the text it is
export function stateOf(text) {
  const trimmed = (text || '').trim();
  if (/^[[{]/.test(trimmed)) {
    try {
      return JSON.parse(trimmed);
    } catch (e) {
      // not json after all
    }
  }
  return text;
}

const filledOptions = (q) => (q.options || []).filter((o) => o.name.trim());
const filledLevels = (q) => (q.levels || []).map((l) => l.trim()).filter(Boolean);

/** What keeps a question from being asked, in words. Null when it is ready. */
export function questionProblem(question, all) {
  const name = question.name.trim();
  if (!name) return 'Give the question a name: the answer comes back under it.';
  if (all.filter((q) => q.name.trim() === name).length > 1) return `Two questions are named "${name}".`;
  if (!question.instructions.trim()) return 'Write the question to ask.';
  if (question.type === 'choice') {
    const options = filledOptions(question);
    if (options.length < 2) return 'A choice needs at least two options.';
    if (new Set(options.map((o) => o.name.trim())).size !== options.length) return 'Two options have the same name.';
  }
  if (question.type === 'score' && filledLevels(question).length < 2) return 'A score needs at least two levels.';
  return null;
}

export const questionsReady = (questions) => questions.length > 0 && questions.every((q) => questionProblem(q, questions) === null);

/** The `questions` of a System One request. */
export function toQuestions(questions) {
  return Object.fromEntries(
    questions.map((q) => {
      const question = { type: q.type, instructions: q.instructions.trim() };
      if (q.type === 'choice') question.criteria = Object.fromEntries(filledOptions(q).map((o) => [o.name.trim(), o.description.trim() || o.name.trim()]));
      if (q.type === 'score') question.criteria = filledLevels(q);
      return [q.name.trim(), question];
    })
  );
}

export const percent = (value) => `${Math.round((Number(value) || 0) * 100)}%`;

/** The outcomes of an answer with their probability, the most likely first for a choice, in order for a score. */
export function outcomesOf(answer) {
  const probabilities = answer.probabilities || {};
  if (answer.type === 'score') {
    const legend = answer.legend || {};
    return Object.keys(probabilities)
      .sort((a, b) => Number(a) - Number(b))
      .map((level) => ({ id: level, label: typeof legend[level] === 'string' ? legend[level] : `Level ${level}`, probability: Number(probabilities[level]) || 0 }));
  }
  return Object.keys(probabilities)
    .map((name) => ({ id: name, label: name, probability: Number(probabilities[name]) || 0, chosen: name === answer.choice }))
    .sort((a, b) => b.probability - a.probability);
}
