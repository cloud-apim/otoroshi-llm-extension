import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import styles from './index.module.css';

const stats = [
  { number: '50+', label: 'LLM Providers' },
  { number: '20+', label: 'Guardrail Rules' },
  { number: '17+', label: 'Workflow Functions' },
  { number: '16+', label: 'Embedding Providers' },
];

const features = [
  {
    icon: '🔗',
    title: 'Unified API',
    description: 'One OpenAI-compatible API to rule them all. Connect any LLM provider without changing a single line of client code.',
  },
  {
    icon: '⚖️',
    title: 'Load Balancing & Failover',
    description: 'Distribute workloads across providers and automatically switch during outages. Zero downtime, always.',
  },
  {
    icon: '🛡️',
    title: '20+ Guardrails',
    description: 'Block prompt injections, PII leakage, toxic content, and more. Validate every request and response with built-in rules.',
  },
  {
    icon: '💰',
    title: 'Cost Management',
    description: 'Real-time cost tracking, budget limits per provider, token quotas per consumer. Never get an unexpected bill again.',
  },
  {
    icon: '🧠',
    title: 'Semantic Caching',
    description: 'Embedding-based similarity matching reduces costs and latency. Exact-match and semantic caches working together.',
  },
  {
    icon: '🔐',
    title: 'Enterprise Security',
    description: 'Granular API keys, role-based access control, secret vault integration. LLM access as secure as your APIs.',
  },
  {
    icon: '🤖',
    title: 'AI Agents & MCP',
    description: 'Build agentic workflows with tool calling, agent handoffs, persistent memory, and Model Context Protocol support.',
  },
  {
    icon: '📊',
    title: 'Full Observability',
    description: 'Audit every request, track environmental impact, export metrics to your favorite dashboards and SIEM tools.',
  },
  {
    icon: '🎨',
    title: 'Multi-Modal',
    description: 'Text, images, audio, video — generate and process any modality through the same gateway with dedicated APIs.',
  },
];

const whyCards = [
  {
    icon: '🏗️',
    title: 'Built on Otoroshi',
    description: 'Leverage a battle-tested, cloud-native API gateway. Get mTLS, service mesh, plugins, and admin UI out of the box. Your LLM gateway inherits enterprise-grade infrastructure.',
  },
  {
    icon: '🔄',
    title: 'Hot-Reload Everything',
    description: 'Change providers, update guardrails, adjust budgets — all without restart or downtime. Configuration changes apply instantly across your entire LLM fleet.',
  },
  {
    icon: '🧩',
    title: 'Extensible by Design',
    description: 'Custom guardrails via WASM, webhooks, or LLM-based validation. Function calling through QuickJS, HTTP, or Otoroshi workflows. Extend everything without forking.',
  },
  {
    icon: '🌍',
    title: 'Sovereign & Open Source',
    description: 'Run on your infrastructure, keep your data where it belongs. Support for EU/French providers like OVH, Scaleway, Cloud Temple. Fully open source under Apache 2.0.',
  },
];

const useCases = [
  {
    icon: '🏢',
    title: 'Enterprise AI Gateway',
    description: 'Centralize all LLM access for your organization with security, quotas, and compliance built in.',
  },
  {
    icon: '🔀',
    title: 'Multi-Provider Strategy',
    description: 'Avoid vendor lock-in by routing requests to the best provider for each use case with automatic failover.',
  },
  {
    icon: '🛡️',
    title: 'Compliance & Governance',
    description: 'Enforce content policies, audit every interaction, track costs, and meet regulatory requirements.',
  },
  {
    icon: '⚡',
    title: 'Performance at Scale',
    description: 'Cache responses, balance load, rate-limit consumers, and keep latency low across millions of requests.',
  },
];

const providers = [
  'OpenAI', 'Anthropic', 'Google Gemini', 'Mistral', 'Azure OpenAI',
  'Ollama', 'Groq', 'Cohere', 'Deepseek', 'X.ai',
  'Cloudflare', 'OVH AI', 'Scaleway', 'Hugging Face',
  'Meta Llama', 'OpenRouter', 'Perplexity', 'Together AI',
  'Fireworks AI', 'DeepInfra', 'SambaNova', 'Lambda Labs',
];

function HomepageHeader() {
  return (
    <header className={styles.heroBanner}>
      <div className="container">
        <div className={styles.heroLayout}>
          <div className={styles.heroContent}>
            <div className={styles.heroTagline}>Open Source AI Gateway</div>
            <Heading as="h1" className={styles.heroTitle}>
              The <span className={styles.heroTitleAccent}>AI Gateway</span> Your Infrastructure Deserves
            </Heading>
            <p className={styles.heroSubtitle}>
              Route, secure, and optimize all your LLM traffic through a single,
              unified gateway. 50+ providers, enterprise-grade guardrails,
              and full cost control — powered by Otoroshi.
            </p>
            <div className={styles.heroButtons}>
              <Link className={styles.heroPrimary} to="/docs/overview">
                Get Started
              </Link>
              <Link
                className={styles.heroSecondary}
                href="https://github.com/cloud-apim/otoroshi-llm-extension/releases/latest">
                Download
              </Link>
              <Link
                className={styles.heroGithub}
                href="https://github.com/cloud-apim/otoroshi-llm-extension">
                GitHub
              </Link>
            </div>
          </div>
          <div className={styles.heroMascotWrapper}>
            <img
              src={require('@site/static/img/otoroshi-llm-extension-logo-no-bg-no-text.png').default}
              alt="Otoroshi LLM Extension"
              className={styles.heroMascot}
            />
          </div>
        </div>
      </div>
    </header>
  );
}

function StatsStrip() {
  return (
    <section className={styles.statsStrip}>
      <div className="container">
        <div className={styles.statsGrid}>
          {stats.map((stat, idx) => (
            <div key={idx} className={styles.statItem}>
              <span className={styles.statNumber}>{stat.number}</span>
              <span className={styles.statLabel}>{stat.label}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function FeaturesSection() {
  return (
    <section className={styles.featuresSection}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <div className={styles.sectionTag}>Capabilities</div>
          <Heading as="h2" className={styles.sectionTitle}>
            Everything You Need to Manage LLMs in Production
          </Heading>
          <p className={styles.sectionSubtitle}>
            From routing to guardrails, from caching to cost control —
            a complete toolkit for production AI infrastructure.
          </p>
        </div>
        <div className={styles.featuresGrid}>
          {features.map((feature, idx) => (
            <div key={idx} className={styles.featureCard}>
              <span className={styles.featureIcon}>{feature.icon}</span>
              <div className={styles.featureTitle}>{feature.title}</div>
              <p className={styles.featureDesc}>{feature.description}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function WhySection() {
  return (
    <section className={styles.whySection}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <div className={styles.sectionTag}>Why Choose Us</div>
          <Heading as="h2" className={styles.sectionTitle}>
            Why Otoroshi LLM Extension?
          </Heading>
          <p className={styles.sectionSubtitle}>
            Not just another proxy. A full-featured AI gateway built
            for teams that take production seriously.
          </p>
        </div>
        <div className={styles.whyGrid}>
          {whyCards.map((card, idx) => (
            <div key={idx} className={styles.whyCard}>
              <span className={styles.whyIcon}>{card.icon}</span>
              <div className={styles.whyTitle}>{card.title}</div>
              <p className={styles.whyDesc}>{card.description}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function ProvidersSection() {
  return (
    <section className={styles.providersSection}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <div className={styles.sectionTag}>Integrations</div>
          <Heading as="h2" className={styles.sectionTitle}>
            50+ LLM Providers, One API
          </Heading>
          <p className={styles.sectionSubtitle}>
            Connect to any major LLM provider through a single, consistent
            OpenAI-compatible interface. Switch providers in seconds.
          </p>
        </div>
        <div className={styles.providersCloud}>
          {providers.map((provider, idx) => (
            <span key={idx} className={styles.providerBadge}>{provider}</span>
          ))}
          <span className={`${styles.providerBadge} ${styles.providerMore}`}>
            +30 more
          </span>
        </div>
      </div>
    </section>
  );
}

function UseCasesSection() {
  return (
    <section className={styles.useCasesSection}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <div className={styles.sectionTag}>Use Cases</div>
          <Heading as="h2" className={styles.sectionTitle}>
            Built for Real-World Scenarios
          </Heading>
          <p className={styles.sectionSubtitle}>
            From startups to enterprises, deploy AI with confidence.
          </p>
        </div>
        <div className={styles.useCasesGrid}>
          {useCases.map((uc, idx) => (
            <div key={idx} className={styles.useCaseCard}>
              <span className={styles.useCaseIcon}>{uc.icon}</span>
              <div className={styles.useCaseTitle}>{uc.title}</div>
              <p className={styles.useCaseDesc}>{uc.description}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function CtaSection() {
  return (
    <section className={styles.ctaSection}>
      <div className="container">
        <div className={styles.ctaBox}>
          <div className={styles.ctaContent}>
            <Heading as="h2" className={styles.ctaTitle}>
              Ready to Take Control of Your AI Infrastructure?
            </Heading>
            <p className={styles.ctaSubtitle}>
              Get started in minutes. Open source, free forever.
            </p>
            <div className={styles.ctaButtons}>
              <Link className={styles.heroPrimary} to="/docs/overview">
                Read the Docs
              </Link>
              <Link
                className={styles.heroSecondary}
                href="https://discord.gg/YRc8WEQU3E">
                Join the Community
              </Link>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

export default function Home() {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout
      title="The AI Gateway Your Infrastructure Deserves"
      description="Route, secure, and optimize all your LLM traffic through a single gateway. 50+ providers, guardrails, cost control — powered by Otoroshi.">
      <HomepageHeader />
      <main>
        <StatsStrip />
        <FeaturesSection />
        <WhySection />
        <ProvidersSection />
        <UseCasesSection />
        <CtaSection />
      </main>
    </Layout>
  );
}
