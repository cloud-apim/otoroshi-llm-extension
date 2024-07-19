import clsx from 'clsx';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

const FeatureList = [
  {
    title: 'Provider agnostic',
    Svg: require('@site/static/img/undraw_proud_coder_re_exuy.svg').default,
    description: (
      <>
        The Otoroshi LLM Extension is able to connect with popular LLM providers such as OpenAI, Mistral, Anthropic, Azure, Ollama, etc. while providing a common API for consumers.
      </>
    ),
  },
  {
    title: 'Prompt engineering',
    Svg: require('@site/static/img/undraw_pair_programming_re_or4x.svg' ).default,
    description: (
      <>
        The Otoroshi LLM Extension offer various way to customize your prompts and implements LLM compliance and governance.
      </>
    ),
  },
  {
    title: 'Monitor and secure usage of LLMs',
    Svg: require('@site/static/img/undraw_hacker_mind_-6-y85.svg').default,
    description: (
      <>
        Validate LLM payloads with ease. Secure access by providing fine grained apikeys with specific access rights. Monitor and limit usages of specific providers to avoid unexpected costs.
      </>
    ),
  },
  {
    title: 'Cloud APIM integration',
    Svg: require('@site/static/img/cloud-apim-logo.svg').default,
    description: (
      <>
        The Otoroshi LLM Extension is available on Cloud APIM managed instance and Cloud APIM Serverless.
      </>
    ),
  },
];

function Feature({Svg, title, description}) {
  return (
    <div className={clsx('col col--4')}>
      <div className="text--center">
        <Svg className={styles.featureSvg} role="img" />
      </div>
      <div className="text--center padding-horiz--md">
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures() {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row" style={{ justifyContent: 'center' }}>
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
