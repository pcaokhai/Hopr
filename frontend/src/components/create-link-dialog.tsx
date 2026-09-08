"use client";

import { useState, type FormEvent } from "react";
import { Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useDashboardStore } from "@/lib/dashboard-store";
import { isValidAlias } from "@/lib/alias";

export function CreateLinkDialog() {
  const addLink = useDashboardStore((s) => s.addLink);
  const [open, setOpen] = useState(false);
  const [destination, setDestination] = useState("");
  const [slug, setSlug] = useState("");
  const [error, setError] = useState<string | null>(null);

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    const finalSlug = slug || Math.random().toString(36).slice(2, 7);
    if (!isValidAlias(finalSlug)) {
      setError("Slug must be 3-20 chars, alphanumeric start/end, no -- or __.");
      return;
    }
    addLink({
      slug: finalSlug,
      shortUrl: `hopr.sh/${finalSlug}`,
      destination,
      clicks: 0,
      createdAt: new Date().toISOString(),
      tags: [],
    });
    setDestination("");
    setSlug("");
    setError(null);
    setOpen(false);
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger
        render={
          <Button>
            <Plus /> Create link
          </Button>
        }
      />
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create a new link</DialogTitle>
          <DialogDescription>
            Demo only — this adds a row to the mock dashboard, it does not
            call the real API.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor="destination">Destination URL</Label>
            <Input
              id="destination"
              type="url"
              required
              value={destination}
              onChange={(e) => setDestination(e.target.value)}
              placeholder="https://example.com/page"
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="slug">Custom slug (optional)</Label>
            <Input
              id="slug"
              value={slug}
              onChange={(e) => setSlug(e.target.value)}
              placeholder="my-link"
              className="font-mono"
            />
          </div>
          {error && <p className="text-sm text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="submit">Create</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
