import Link from "next/link";
import { ArrowRight, Gauge, Link2, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ShortenForm } from "@/components/shorten-form";

const PILLARS = [
  {
    icon: Gauge,
    title: "Redirects at gateway speed",
    body: "A dedicated resolver service and Redis-backed keygen keep every redirect fast, with no cold starts.",
  },
  {
    icon: Link2,
    title: "Slugs you control",
    body: "Bring your own alias or let Hopr generate one — either way you get a short, memorable link.",
  },
  {
    icon: ShieldCheck,
    title: "Built to scale",
    body: "Sharded Redis and a stateless shortener service mean Hopr grows with your traffic, not against it.",
  },
];

export default function Home() {
  return (
    <div className="flex flex-col">
      <section className="border-b">
        <div className="mx-auto flex max-w-5xl flex-col items-start gap-8 px-6 py-24 sm:py-32">
          <span className="rounded-full border px-3 py-1 font-mono text-xs text-muted-foreground">
            hopr.sh
          </span>
          <h1 className="text-4xl font-semibold tracking-tight sm:text-6xl">
            Short links,
            <br />
            shipped instantly.
          </h1>
          <p className="max-w-xl text-lg text-muted-foreground">
            Paste a long URL, get a short one back in the time it takes to
            blink. No sign-up required to try it.
          </p>
          <ShortenForm />
        </div>
      </section>

      <section className="border-b bg-secondary/30">
        <div className="mx-auto grid max-w-5xl gap-8 px-6 py-20 sm:grid-cols-3">
          {PILLARS.map(({ icon: Icon, title, body }) => (
            <div key={title} className="flex flex-col gap-3">
              <Icon className="size-5 text-primary" />
              <h3 className="font-medium">{title}</h3>
              <p className="text-sm text-muted-foreground">{body}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="border-b">
        <div className="mx-auto max-w-5xl px-6 py-16 text-center">
          <p className="font-mono text-sm text-muted-foreground">
            Trusted by teams shipping links for
          </p>
          <div className="mt-6 flex flex-wrap items-center justify-center gap-x-10 gap-y-4 text-lg font-semibold tracking-tight text-muted-foreground/70">
            <span>Acme Corp</span>
            <span>Nimbus</span>
            <span>Loopline</span>
            <span>Fjord Labs</span>
            <span>Kestrel</span>
          </div>
        </div>
      </section>

      <section>
        <div className="mx-auto flex max-w-5xl flex-col items-center gap-6 px-6 py-24 text-center">
          <h2 className="text-3xl font-semibold tracking-tight">
            See every click that matters
          </h2>
          <p className="max-w-md text-muted-foreground">
            A dashboard with click analytics, top referrers, and device
            breakdowns — preview it now with sample data.
          </p>
          <Button
            size="lg"
            nativeButton={false}
            render={
              <Link href="/dashboard">
                Open the dashboard <ArrowRight />
              </Link>
            }
          />
        </div>
      </section>
    </div>
  );
}
