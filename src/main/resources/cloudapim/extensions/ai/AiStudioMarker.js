// Entities created from AI Studio are ordinary entities carrying `ai_studio` metadata. Surfacing that here
// saves opening the metadata to know an entity belongs to a workspace — and warns that the studio owns the
// fields it manages, so an edit made in this console can be rewritten on the next save over there.

const AI_STUDIO_FLAG = 'ai_studio';
const AI_STUDIO_WORKSPACE = 'ai_studio_workspace';

function aiStudioWorkspaceOf(item) {
  const metadata = (item && item.metadata) || {};
  if (metadata[AI_STUDIO_FLAG] !== 'true') return null;
  return metadata[AI_STUDIO_WORKSPACE] || '';
}

function aiStudioTitle(workspaceId) {
  const where = workspaceId ? ` (workspace ${workspaceId})` : '';
  return `Created from AI Studio${where}`;
}

function aiStudioBadge(item, style) {
  const workspaceId = aiStudioWorkspaceOf(item);
  if (workspaceId === null) return null;
  const props = {
    className: 'badge bg-info',
    style: { textDecoration: 'none', ...(style || {}) },
    title: aiStudioTitle(workspaceId),
  };
  if (!workspaceId) return React.createElement('span', props, 'AI Studio');
  return React.createElement(
    'a',
    { ...props, href: `/extensions/cloud-apim/ai-studio/workspaces/${workspaceId}/overview`, onClick: (e) => e.stopPropagation() },
    'AI Studio'
  );
}

// a column for the entity tables: empty for an entity created here, a badge for one coming from the studio
const aiStudioColumn = {
  title: 'Origin',
  filterId: `metadata.${AI_STUDIO_FLAG}`,
  style: { textAlign: 'center', width: 90 },
  notSortable: true,
  content: (item) => (aiStudioWorkspaceOf(item) === null ? '' : 'AI Studio'),
  cell: (value, item) => aiStudioBadge(item) || React.createElement('span', null, ''),
};

// the same marker on the entity itself, as a read-only row of the form
class AiStudioOrigin extends Component {
  render() {
    const badge = aiStudioBadge(this.props.rawValue, { fontSize: 12, padding: '5px 9px' });
    if (!badge) return null;
    return React.createElement(
      'div',
      { className: 'row mb-3' },
      React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'Origin'),
      React.createElement(
        'div',
        { className: 'col-sm-10', style: { display: 'flex', alignItems: 'center', gap: 10 } },
        badge,
        // the theme variable, not bootstrap's `.text-muted`: that one is unreadable on the dark theme
        React.createElement(
          'span',
          { style: { color: 'var(--text-muted)' } },
          'Editing it here is fine: a save from the studio only rewrites the fields its own forms manage.'
        )
      )
    );
  }
}

const aiStudioOriginField = { ai_studio_origin: { type: AiStudioOrigin } };
const aiStudioOriginFlow = ['ai_studio_origin'];
