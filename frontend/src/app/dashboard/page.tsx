"use client";

import Link from "next/link";
import { Search } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { MockBadge } from "@/components/mock-badge";
import { CreateLinkDialog } from "@/components/create-link-dialog";
import { useDashboardStore } from "@/lib/dashboard-store";

export default function DashboardPage() {
  const links = useDashboardStore((s) => s.links);
  const search = useDashboardStore((s) => s.search);
  const setSearch = useDashboardStore((s) => s.setSearch);

  const filtered = links.filter((l) => {
    const q = search.toLowerCase();
    return (
      l.slug.toLowerCase().includes(q) ||
      l.destination.toLowerCase().includes(q) ||
      l.tags.some((t) => t.toLowerCase().includes(q))
    );
  });

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 px-6 py-12">
      <div className="flex flex-col gap-2">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-semibold tracking-tight">Links</h1>
          <MockBadge />
        </div>
        <p className="text-sm text-muted-foreground">
          You&apos;re viewing the dashboard as a demo — there is no real
          authentication yet.
        </p>
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="relative w-full max-w-sm">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search links..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-8"
          />
        </div>
        <CreateLinkDialog />
      </div>

      <div className="rounded-xl border shadow-sm">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Short link</TableHead>
              <TableHead>Destination</TableHead>
              <TableHead className="text-right">Clicks</TableHead>
              <TableHead>Created</TableHead>
              <TableHead>Tags</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {filtered.map((link) => (
              <TableRow key={link.slug}>
                <TableCell className="font-mono text-primary">
                  <Link href={`/dashboard/${link.slug}`} className="hover:underline">
                    {link.shortUrl}
                  </Link>
                </TableCell>
                <TableCell className="max-w-xs truncate text-muted-foreground">
                  {link.destination}
                </TableCell>
                <TableCell className="text-right font-mono">
                  {link.clicks.toLocaleString()}
                </TableCell>
                <TableCell className="text-muted-foreground">
                  {new Date(link.createdAt).toLocaleDateString()}
                </TableCell>
                <TableCell>
                  <div className="flex flex-wrap gap-1">
                    {link.tags.map((tag) => (
                      <Badge key={tag} variant="secondary">
                        {tag}
                      </Badge>
                    ))}
                  </div>
                </TableCell>
              </TableRow>
            ))}
            {filtered.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="text-center text-muted-foreground">
                  No links match your search.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
