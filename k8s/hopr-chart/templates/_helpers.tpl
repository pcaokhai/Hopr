{{/*
Zone-spreading constraints for a Deployment's pod spec. Call with the app label, e.g.
  {{- include "hopr.topologySpreadConstraints" (dict "app" "resolver-service" "root" $) | nindent 6 }}

topologySpreadConstraints rather than podAntiAffinity: anti-affinity can only express
"never co-locate" (required) or "prefer not to" (preferred, with an opaque weight), while
a spread constraint expresses the thing actually wanted -- keep the per-zone pod counts
within `maxSkew` of each other -- and is what the scheduler's own scoring is built around
today. Anti-affinity remains the right tool only when the rule really is binary.

whenUnsatisfiable is ScheduleAnyway, i.e. a soft preference: the scheduler prefers the
least-loaded zone but will still place the pod when no placement satisfies the skew.
That is deliberate for this repo, whose local cluster is a single-node kind cluster with
no `topology.kubernetes.io/zone` labels at all -- under DoNotSchedule every replica past
the first would be permanently Pending locally. The trade-off is that on a real
multi-zone cluster ScheduleAnyway can silently degrade to "all replicas in one zone"
under pressure; a production values file should override whenUnsatisfiable to
DoNotSchedule once the cluster actually has zones.
*/}}
{{- define "hopr.topologySpreadConstraints" -}}
{{- with .root.Values.topologySpread }}
{{- if .enabled }}
topologySpreadConstraints:
  - maxSkew: {{ .maxSkew }}
    topologyKey: {{ .topologyKey }}
    whenUnsatisfiable: {{ .whenUnsatisfiable }}
    labelSelector:
      matchLabels:
        app: {{ $.app }}
{{- end }}
{{- end }}
{{- end -}}
