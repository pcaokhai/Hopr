// ponytail: static fixtures generated once at module load — no need for a seeded RNG
// or a backend, this is demo data until the API grows list/analytics endpoints.
export interface MockLink {
  slug: string;
  shortUrl: string;
  destination: string;
  clicks: number;
  createdAt: string;
  tags: string[];
}

export interface ClickPoint {
  date: string;
  clicks: number;
}

export interface Breakdown {
  label: string;
  value: number;
}

export interface LinkAnalytics {
  clicksOverTime: ClickPoint[];
  topCountries: Breakdown[];
  topDevices: Breakdown[];
  topReferrers: Breakdown[];
}

const DESTINATIONS = [
  "https://github.com/anthropics/claude-code",
  "https://nextjs.org/docs/app",
  "https://ui.shadcn.com/docs/components",
  "https://tailwindcss.com/docs/installation",
  "https://vercel.com/changelog",
  "https://react.dev/learn",
  "https://zustand.docs.pmnd.rs/getting-started/introduction",
  "https://www.figma.com/community",
];

const TAG_POOL = ["launch", "docs", "marketing", "internal", "social", "product"];

function seededLinks(): MockLink[] {
  return DESTINATIONS.map((destination, i) => {
    const slug = ["x7f2a", "q9k3m", "r4t8p", "z1v6n", "m2c5w", "k8d3j", "p6h1s", "n3f9b"][i];
    return {
      slug,
      shortUrl: `hopr.sh/${slug}`,
      destination,
      clicks: Math.floor(80 + (i * 137 + 43) % 4000),
      createdAt: new Date(Date.now() - (i + 1) * 3 * 86400000).toISOString(),
      tags: [TAG_POOL[i % TAG_POOL.length], TAG_POOL[(i + 2) % TAG_POOL.length]],
    };
  });
}

export const MOCK_LINKS: MockLink[] = seededLinks();

export function mockAnalyticsFor(slug: string): LinkAnalytics {
  const seed = slug.split("").reduce((acc, c) => acc + c.charCodeAt(0), 0);
  const clicksOverTime: ClickPoint[] = Array.from({ length: 14 }, (_, i) => {
    const d = new Date(Date.now() - (13 - i) * 86400000);
    return {
      date: d.toISOString().slice(0, 10),
      clicks: 10 + ((seed + i * 31) % 90),
    };
  });

  return {
    clicksOverTime,
    topCountries: [
      { label: "United States", value: 40 + (seed % 20) },
      { label: "Germany", value: 20 + (seed % 10) },
      { label: "Vietnam", value: 15 + (seed % 8) },
      { label: "Brazil", value: 10 + (seed % 6) },
      { label: "Japan", value: 8 + (seed % 4) },
    ],
    topDevices: [
      { label: "Desktop", value: 55 },
      { label: "Mobile", value: 38 },
      { label: "Tablet", value: 7 },
    ],
    topReferrers: [
      { label: "Direct", value: 34 },
      { label: "twitter.com", value: 22 },
      { label: "google.com", value: 18 },
      { label: "github.com", value: 14 },
      { label: "slack.com", value: 12 },
    ],
  };
}
