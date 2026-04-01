{{/*
Common labels
*/}}
{{- define "k8s-batch.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "k8s-batch.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Full name: release-chart truncated to 63 chars
*/}}
{{- define "k8s-batch.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
MySQL host
*/}}
{{- define "k8s-batch.mysql.host" -}}
{{- printf "%s-mysql" (include "k8s-batch.fullname" .) }}
{{- end }}

{{/*
Generic JDBC URL builder (database-per-service pattern).
Accepts a dict with "ctx" (root context) and "dbName" (database name).
All per-service helpers below delegate to this one.
*/}}
{{- define "k8s-batch.mysql.jdbcUrlFor" -}}
{{- $ctx := .ctx -}}
{{- $dbName := .dbName -}}
{{- if eq $ctx.Values.database.type "oracle" -}}
jdbc:oracle:thin:@//{{ include "k8s-batch.mysql.host" $ctx }}:{{ $ctx.Values.mysql.service.port }}/{{ $dbName }}
{{- else -}}
jdbc:mysql://{{ include "k8s-batch.mysql.host" $ctx }}:{{ $ctx.Values.mysql.service.port }}/{{ $dbName }}?useSSL=false&allowPublicKeyRetrieval=true
{{- end -}}
{{- end }}

{{/*
Batch service JDBC URL (uses mysql.auth.database)
*/}}
{{- define "k8s-batch.mysql.jdbcUrl" -}}
{{- include "k8s-batch.mysql.jdbcUrlFor" (dict "ctx" . "dbName" .Values.mysql.auth.database) -}}
{{- end }}

{{/*
CRUD service JDBC URL (uses mysql.auth.additionalDatabases.crud)
*/}}
{{- define "k8s-batch.mysql.crudJdbcUrl" -}}
{{- include "k8s-batch.mysql.jdbcUrlFor" (dict "ctx" . "dbName" .Values.mysql.auth.additionalDatabases.crud) -}}
{{- end }}

{{/*
Database JDBC driver class name
*/}}
{{- define "k8s-batch.datasource.driverClassName" -}}
{{- if eq .Values.database.type "oracle" -}}
oracle.jdbc.OracleDriver
{{- else -}}
com.mysql.cj.jdbc.Driver
{{- end -}}
{{- end }}

{{/*
Kafka bootstrap servers (computed from replica count)
*/}}
{{- define "k8s-batch.kafka.bootstrapServers" -}}
{{- $fullname := printf "%s-kafka" (include "k8s-batch.fullname" .) -}}
{{- $headless := printf "%s-kafka-headless" (include "k8s-batch.fullname" .) -}}
{{- $servers := list -}}
{{- range $i := until (int .Values.kafka.replicaCount) -}}
  {{- $servers = append $servers (printf "%s-%d.%s:%d" $fullname $i $headless (int $.Values.kafka.service.port)) -}}
{{- end -}}
{{- join "," $servers }}
{{- end }}

{{/*
Schema Registry URL
*/}}
{{- define "k8s-batch.schemaRegistry.url" -}}
http://{{ include "k8s-batch.fullname" . }}-schema-registry:{{ .Values.schemaRegistry.service.port }}
{{- end }}

{{/*
MySQL secret name
*/}}
{{- define "k8s-batch.mysql.secretName" -}}
{{- if .Values.mysql.auth.existingSecret -}}
  {{- .Values.mysql.auth.existingSecret }}
{{- else -}}
  {{- printf "%s-mysql" (include "k8s-batch.fullname" .) }}
{{- end }}
{{- end }}
