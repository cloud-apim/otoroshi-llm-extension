// @ts-check
// `@type` JSDoc annotations allow editor autocompletion and type checking
// (when paired with `@ts-check`).
// There are various equivalent ways to declare your Docusaurus config.
// See: https://docusaurus.io/docs/api/docusaurus-config

import {themes as prismThemes} from 'prism-react-renderer';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Otoroshi LLM Extension',
  tagline: 'A set of Otoroshi plugins to interact with LLMs',
  favicon: 'img/otoroshi-llm-extension-logo.png',

  // Set the production url of your site here
  url: 'https://cloud-apim.github.io',
  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: '/otoroshi-llm-extension',

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: 'cloud-apim', // Usually your GitHub org/user name.
  projectName: 'otoroshi-llm-extension', // Usually your repo name.

  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: './sidebars.js',
        },
        theme: {
          customCss: './src/css/custom.css',
        },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      // Replace with your project's social card
      image: 'img/otoroshi-llm-extension-logo.png',
      navbar: {
        title: 'otoroshi-llm-extension',
        logo: {
          alt: 'otoroshi-llm-extension Logo',
          src: 'img/otoroshi-llm-extension-logo.png',
        },
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'tutorialSidebar',
            position: 'left',
            label: 'Documentation',
          },
          {
            href: 'https://github.com/cloud-apim/otoroshi-llm-extension',
            label: 'GitHub',
            position: 'right',
          },
          {
            label: 'Cloud APIM',
            href: 'https://www.cloud-apim.com',
            position: 'right',
          },
          {
            href: 'https://blog.cloud-apim.com',
            label: 'Cloud APIM Blog', 
            position: 'right'
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Docs',
            items: [
              {
                label: 'Documentation',
                to: '/docs/overview',
              },
            ],
          },
          {
            title: 'Community',
            items: [
              {
                label: 'Discord',
                href: 'https://discord.gg/YRc8WEQU3E',
              },
              {
                label: 'Twitter',
                href: 'https://twitter.com/cloudapim',
              },
              {
                label: 'Youtube',
                href: 'https://www.youtube.com/@CloudAPIM',
              },
            ],
          },
          {
            title: 'More',
            items: [
              {
                label: 'Cloud APIM',
                href: 'https://www.cloud-apim.com',
              },
              {
                label: 'Blog',
                href: 'https://blog.cloud-apim.com',
              },
              {
                label: 'GitHub',
                href: 'https://github.com/cloud-apim/otoroshi-llm-extension',
              },
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} Cloud APIM Built with Docusaurus.`,
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        additionalLanguages: ['bash', 'shell-session' ],
      },
    }),
};

export default config;
