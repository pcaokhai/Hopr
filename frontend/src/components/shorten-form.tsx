"use client";

import { useState, type FormEvent } from "react";
import { Check, Copy, Loader2, QrCode } from "lucide-react";
import { QRCodeSVG } from "qrcode.react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ApiError, shorten } from "@/lib/api";
import { isValidAlias } from "@/lib/alias";
import { cn } from "cn";

type Status = "idle" | "resolving" | "done" | "error";

export function ShortenForm() {
  const [longUrl, setLongUrl] = useState("");
  const [alias, setAlias] = useState("");
  const [status, setStatus] = useState<Status>("idle");
  const [error, setError] = useState<string | null>(null);
  const [shortUrl, setShortUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [showQr, setShowQr] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    if (alias && !isValidAlias(alias)) {
      setError(
        "Custom slug must be 3-20 characters, alphanumeric start/end, no consecutive - or _.",
      );
      return;
    }

    setStatus("resolving");
    setShortUrl(null);
    setShowQr(false);
    try {
      const res = await shorten(longUrl, alias || undefined);
      setShortUrl(res.shortUrl);
      setStatus("done");
    } catch (err) {
      setStatus("error");
      setError(err instanceof ApiError ? err.message : "Something went wrong.");
    }
  }

  async function copyToClipboard() {
    if (!shortUrl) return;
    await navigator.clipboard.writeText(shortUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  return (
    <div className="w-full max-w-xl">
      <form onSubmit={onSubmit} className="flex flex-col gap-3 sm:flex-row sm:items-start">
        <div className="flex-1 flex flex-col gap-2">
          <Label htmlFor="longUrl" className="sr-only">
            Long URL
          </Label>
          <Input
            id="longUrl"
            type="url"
            required
            placeholder="https://your-long-url.example.com/goes/here"
            value={longUrl}
            onChange={(e) => setLongUrl(e.target.value)}
            className="h-11 font-mono text-sm"
          />
          <Input
            id="alias"
            type="text"
            placeholder="custom-slug (optional)"
            value={alias}
            onChange={(e) => setAlias(e.target.value)}
            className="h-9 font-mono text-xs text-muted-foreground"
          />
        </div>
        <Button
          type="submit"
          size="lg"
          disabled={status === "resolving"}
          className="h-11 shrink-0"
        >
          {status === "resolving" ? (
            <Loader2 className="animate-spin" />
          ) : (
            "Shorten"
          )}
        </Button>
      </form>

      {error && <p className="mt-3 text-sm text-destructive">{error}</p>}

      <div
        className={cn(
          "mt-4 grid transition-all duration-300 ease-out",
          shortUrl ? "grid-rows-[1fr] opacity-100" : "grid-rows-[0fr] opacity-0",
        )}
      >
        <div className="overflow-hidden">
          {shortUrl && (
            <div
              className="animate-in fade-in slide-in-from-top-2 duration-300 rounded-xl border bg-card p-4 shadow-sm"
            >
              <div className="flex items-center justify-between gap-3">
                <a
                  href={shortUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="font-mono text-base text-primary hover:underline break-all"
                >
                  {shortUrl.replace(/^https?:\/\//, "")}
                </a>
                <div className="flex shrink-0 gap-1">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => setShowQr((v) => !v)}
                    aria-label="Toggle QR code"
                  >
                    <QrCode />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    onClick={copyToClipboard}
                    aria-label="Copy short link"
                  >
                    {copied ? <Check className="text-primary" /> : <Copy />}
                  </Button>
                </div>
              </div>
              {showQr && (
                <div className="mt-4 flex justify-center rounded-lg bg-white p-3">
                  <QRCodeSVG value={shortUrl} size={128} />
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
