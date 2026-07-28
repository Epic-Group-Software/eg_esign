{{/*
Expand the name of the chart.
*/}}
{{- define "app.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "app.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "app.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "app.labels" -}}
helm.sh/chart: {{ include "app.chart" . }}
{{ include "app.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "app.selectorLabels" -}}
app.kubernetes.io/name: {{ include "app.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* Component labels — pass a dict with "component" and "context" */}}
{{- define "app.componentLabels" -}}
helm.sh/chart: {{ include "app.chart" .context }}
{{ include "app.componentSelectorLabels" . }}
{{- if .context.Chart.AppVersion }}
app.kubernetes.io/version: {{ .context.Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .context.Release.Service }}
{{- end }}

{{- define "app.componentSelectorLabels" -}}
app.kubernetes.io/name: {{ include "app.name" .context }}
app.kubernetes.io/instance: {{ .context.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "app.fqdn" -}}
{{- .Values.fqdn -}}
{{- end }}

{{/*
Postgres connection string. Assembled here rather than in each template so the
app and any job that needs it cannot drift apart.
*/}}
{{- define "app.databaseUrl" -}}
{{- printf "postgres://%s:%s@%s-postgres:%v/%s?sslmode=%s"
      .Values.postgres.auth.username
      .Values.postgres.auth.password
      (include "app.fullname" .)
      .Values.service.postgres.port
      .Values.postgres.auth.database
      .Values.postgres.sslMode -}}
{{- end }}
