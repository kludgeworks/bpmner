#!/usr/bin/env bash

# Copyright 2026 The Project Contributors
# SPDX-License-Identifier: MIT

#MISE description="Run the bpmner app via bazelisk with a provider profile; API key sourced from 1Password"
#MISE dir="{{config_root}}"
#MISE tools={gum="latest"}
#MISE raw=true
#USAGE flag "-p --provider <provider>" help="LLM provider profile (prompts via gum if omitted)" {
#USAGE   choices "anthropic" "openai" "gemini" "mistral" "deepseek" "llama" "githubmodels"
#USAGE }
#USAGE flag "-m --mode <mode>" help="Launch mode (cli or web, prompts via gum if omitted)" {
#USAGE   choices "cli" "web"
#USAGE }
#USAGE flag "-w --web" help="Also activate the web UI profile (browser UI on :8080) [deprecated: use --mode web]"
#USAGE flag "--verbose" help="Also activate the verbose (DEBUG logging) profile"

set -euo pipefail

# Display label : canonical id. gum's --label-delimiter returns the id.
choices=(
  "Anthropic (Claude):anthropic"
  "OpenAI (GPT):openai"
  "Google (Gemini):gemini"
  "Mistral:mistral"
  "DeepSeek:deepseek"
  "Llama via OpenRouter:llama"
  "GitHub Models:githubmodels"
)

# No --provider given: pick one interactively with gum.
provider="${usage_provider:-}"
if [[ -z ${provider} ]]; then
  if ! command -v gum >/dev/null 2>&1; then
    echo "No --provider given and 'gum' is not installed. Run 'mise install', or pass --provider <name>." >&2
    exit 1
  fi
  provider="$(gum choose --header "Select an LLM provider" --label-delimiter=":" "${choices[@]}")"
fi
if [[ -z ${provider} ]]; then
  echo "No provider selected." >&2
  exit 1
fi

# Map the provider to its API-key env var and 1Password item
# (read as op://bpmner/<item>/api-key).
case ${provider} in
anthropic) key_var=ANTHROPIC_API_KEY op_item=anthropic ;;
openai) key_var=OPENAI_API_KEY op_item=openai ;;
gemini) key_var=GEMINI_API_KEY op_item=gemini ;;
mistral) key_var=MISTRAL_API_KEY op_item=mistral ;;
deepseek) key_var=DEEPSEEK_API_KEY op_item=deepseek ;;
llama) key_var=OPENROUTER_API_KEY op_item=openrouter ;;
githubmodels) key_var=GITHUB_TOKEN ;;
*)
  echo "Unknown provider: '${provider}'" >&2
  exit 1
  ;;
esac

if [[ ${provider} == "githubmodels" ]]; then
  if ! key="$(gh auth token)"; then
    echo "Failed to retrieve GitHub token via 'gh auth token'. Please make sure gh CLI is authenticated." >&2
    exit 1
  fi
else
  if ! key="$(op read "op://bpmner/${op_item}/api-key")"; then
    echo "Failed to read op://bpmner/${op_item}/api-key. Run 'op signin', or set OP_SERVICE_ACCOUNT_TOKEN." >&2
    exit 1
  fi
fi
export "${key_var}=${key}"

mode="${usage_mode:-}"
if [[ -z ${mode} ]]; then
  if [[ ${usage_web:-false} == "true" ]]; then
    mode="web"
  else
    mode="$(gum choose --header "Select a launch mode" --label-delimiter=":" "CLI (Terminal):cli" "Web App (Browser):web")"
  fi
fi
if [[ -z ${mode} ]]; then
  echo "No mode selected." >&2
  exit 1
fi

profiles="${provider}"
[[ ${mode} == "web" ]] && profiles="${profiles},web"
[[ ${usage_verbose:-false} == "true" ]] && profiles="${profiles},verbose"
export SPRING_PROFILES_ACTIVE="${profiles}"

bazelisk build //src:bpmner_app
bazel_bin="$(bazelisk info bazel-bin)"
exec java \
  -Dspring.profiles.active="${SPRING_PROFILES_ACTIVE}" \
  -jar "${bazel_bin}/src/bpmner_app.jar"
