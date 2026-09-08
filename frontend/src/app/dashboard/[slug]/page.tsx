"use client";

import { use } from "react";
import { notFound } from "next/navigation";
import { Area, AreaChart, CartesianGrid, XAxis } from "recharts";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  ChartConfig,
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
} from "@/components/ui/chart";
import { MockBadge } from "@/components/mock-badge";
import { useDashboardStore } from "@/lib/dashboard-store";
import { mockAnalyticsFor } from "@/lib/mock-data";

const chartConfig = {
  clicks: { label: "Clicks", color: "var(--primary)" },
} satisfies ChartConfig;

function Breakdown({
  title,
  data,
}: {
  title: string;
  data: { label: string; value: number }[];
}) {
  const max = Math.max(...data.map((d) => d.value));
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        {data.map((d) => (
          <div key={d.label} className="flex items-center gap-3 text-sm">
            <span className="w-28 shrink-0 truncate text-muted-foreground">
              {d.label}
            </span>
            <div className="h-2 flex-1 overflow-hidden rounded-full bg-secondary">
              <div
                className="h-full rounded-full bg-primary"
                style={{ width: `${(d.value / max) * 100}%` }}
              />
            </div>
            <span className="w-10 shrink-0 text-right font-mono">{d.value}</span>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

export default function LinkDetailPage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = use(params);
  const link = useDashboardStore((s) => s.links.find((l) => l.slug === slug));

  if (!link) notFound();

  const analytics = mockAnalyticsFor(slug);

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 px-6 py-12">
      <div className="flex flex-col gap-2">
        <div className="flex items-center gap-3">
          <h1 className="font-mono text-2xl font-semibold tracking-tight text-primary">
            {link.shortUrl}
          </h1>
          <MockBadge label="Analytics" />
        </div>
        <p className="text-sm text-muted-foreground">→ {link.destination}</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Clicks over time</CardTitle>
          <CardDescription>Last 14 days</CardDescription>
        </CardHeader>
        <CardContent>
          <ChartContainer config={chartConfig} className="h-64 w-full">
            <AreaChart data={analytics.clicksOverTime}>
              <CartesianGrid vertical={false} />
              <XAxis
                dataKey="date"
                tickLine={false}
                axisLine={false}
                tickMargin={8}
                tickFormatter={(v: string) => v.slice(5)}
              />
              <ChartTooltip content={<ChartTooltipContent />} />
              <Area
                dataKey="clicks"
                type="monotone"
                fill="var(--color-clicks)"
                fillOpacity={0.15}
                stroke="var(--color-clicks)"
                strokeWidth={2}
                isAnimationActive={false}
              />
            </AreaChart>
          </ChartContainer>
        </CardContent>
      </Card>

      <div className="grid gap-6 sm:grid-cols-3">
        <Breakdown title="Top countries" data={analytics.topCountries} />
        <Breakdown title="Top devices" data={analytics.topDevices} />
        <Breakdown title="Top referrers" data={analytics.topReferrers} />
      </div>
    </div>
  );
}
