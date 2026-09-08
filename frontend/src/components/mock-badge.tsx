import { FlaskConical } from "lucide-react";
import { Badge } from "@/components/ui/badge";

export function MockBadge({ label = "Demo data" }: { label?: string }) {
  return (
    <Badge
      variant="outline"
      className="gap-1 border-dashed text-muted-foreground font-mono text-xs"
    >
      <FlaskConical className="size-3" />
      {label} · not wired to backend
    </Badge>
  );
}
