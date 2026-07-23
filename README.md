# Davinci Sign

Professional electronic signature solution by Davinci AI Solutions.

> **Note:** This project is based on [Davinci Sign](https://github.com/documenso/documenso), an open-source document signing platform. We extend our gratitude to the Davinci Sign team for their excellent work.

<p align="center" style="margin-top: 20px">
  <p align="center">
  The Open Source DocuSign Alternative.
  <br>
    <a href="https://davincisolutions.ai"><strong>Learn more »</strong></a>
    <br />
    <br />
    <a href="https://documen.so/discord">Discord</a>
    ·
    <a href="https://davincisolutions.ai">Website</a>
    ·
    <a href="https://docs.davincisolutions.ai">Documentation</a>
    ·
    <a href="https://github.com/documenso/documenso/issues">Issues</a>
    ·
    <a href="https://documen.so/live">Upcoming Releases</a>
    ·
    <a href="https://documen.so/roadmap">Roadmap</a>
  </p>
</p>

---

## Epic Group e-Sign — Deployment Objective & Status

> This fork (`Davinci-Technology/eg_esign`) exists to stand up a **dedicated, isolated Documenso instance for Epic Group**, fully separated from the SaaS `davinci-sign` instance. It is the Stage-2 destination of the eg_intranet DocuSign → Quill → Documenso e-signature migration.

**Objective:** Two environments — **prod** (`esign.epicgroup.ca`, branch `main` → namespace `eg-esign-prod`) and **staging** (`esign-staging.epicgroup.ca`, branch `staging` → namespace `eg-esign-staging`) — on the **Epic Group AKS cluster**, built and shipped by **Jenkins** from this fork (`main → prod`, `staging → staging`), with its own registry (`epicregistry.azurecr.io`), in-cluster Postgres + MinIO, Azure AD (Epic Group tenant) SSO, and signup locked to `epicgroup.ca`.

**Detailed plan:** [`docs/superpowers/plans/2026-07-09-eg-esign-ci-pipeline.md`](docs/superpowers/plans/2026-07-09-eg-esign-ci-pipeline.md) · **Deploy runbook** lives in the `eg_intranet` repo (`dev_docs/migration/documenso-eg-instance-deployment-runbook.md`).

### ✅ Done — repo-side deliverables (branch `feature/eg-esign-ci`, PR #1)

| # | Deliverable | Location |
|---|---|---|
| 1 | Fork created from `Davinci-Technology/documenso` (`upstream` remote wired, periodic upstream-sync branches) | this repo |
| 2 | Kustomize **base** — app deployment, configmap (OIDC on, signup locked), ingress, PDB | `k8s/eg-esign/base/` |
| 3 | **In-cluster Postgres 17** StatefulSet + PVC (new — SaaS used Azure PostgreSQL) | `k8s/eg-esign/base/postgres-*.yaml` |
| 4 | **In-cluster MinIO** StatefulSet + PVC (S3 upload transport) | `k8s/eg-esign/base/minio-*.yaml` |
| 5 | **staging + prod overlays** — namespace, host, TLS secret, image tag per env | `k8s/eg-esign/overlays/{staging,prod}/` |
| 6 | **`Jenkinsfile.eg`** — multibranch branch→env→namespace pipeline (Kaniko build → `epicregistry.azurecr.io` → `kubectl apply -k`, health-check + rollout-undo) | `Jenkinsfile.eg` |
| 7 | CI pipeline implementation plan (ground-truth deltas documented) | `docs/superpowers/plans/2026-07-09-...md` |

### ⛔ Not Done — merge, external wiring & go-live (CI plan Task 6)

| # | Item | Blocking state |
|---|---|---|
| 1 | **Merge PR #1** — repo work lives only on `feature/eg-esign-ci`; neither `main` nor `staging` carries `k8s/eg-esign/` or `Jenkinsfile.eg` yet | PR #1 **OPEN, unmerged** |
| 2 | Publish infra to **`main` + `staging`** deploy branches | not done |
| 3 | **DNS**: `esign.epicgroup.ca` + `esign-staging.epicgroup.ca` → `130.107.18.187` (eg ingress) | both records **do not resolve (NXDOMAIN)** |
| 4 | **Jenkins multibranch job** "eg-esign" created & scanned | not created — pipeline **never run** |
| 5 | Jenkins **credentials**: `eg-aks-kubeconfig`, `epicregistry-*`, per-env `eg-esign-*` secrets (nextauth, **encryption keys**, postgres/minio/smtp passwords, cert passphrase, `.p12`) | not provisioned |
| 6 | **Azure AD app registration** (Epic Group tenant, OIDC redirect URIs) | not created |
| 7 | Signing **`.p12` certificate** (real Epic Group cert for prod) | not provisioned |
| 8 | **Live instances** deployed (`/api/health` green, LE TLS) in either namespace | nothing deployed |
| 9 | End-to-end verify (Azure AD sign-in → upload → sign → download) | not run |
| 10 | **eg_intranet wiring** — Documenso `ServiceTenant` + webhook secret pointed at this instance; staging canary | not started |

**Bottom line:** the repo-side CI/CD + k8s scaffolding is **complete and reviewed on PR #1**, but **nothing is deployed** — no merge, no DNS, no Jenkins job, no live instance. Go-live is gated on merging PR #1 and completing the external one-time wiring above.

---

## About Davinci Sign

Davinci Sign provides a fast, secure, and easy document signing experience for businesses. Built on the robust Davinci Sign platform, it offers:

- Secure electronic signatures
- Self-hosting capability
- Full control over your document signing infrastructure
- Integration with your existing workflows

## Community and Next Steps 🎯

- Try Davinci Sign by self-hosting it or visiting [davincisolutions.ai](https://davincisolutions.ai).
- Tell us what you think in the [Davinci Sign Discussions](https://github.com/documenso/documenso/discussions).
- Join the [Discord server](https://documen.so/discord) for any questions and getting to know other community members.
- ⭐ the repository to help us raise awareness.
- Open detailed [issues](https://github.com/documenso/documenso/issues) to report bugs or propose features.

## Contributing

> **Note**: We no longer accept external pull requests, aside from a small group of trusted contributors we reach out to directly. The best way to contribute is through detailed issues. Read [Why We're Pausing External Pull Requests](https://davincisolutions.ai/blog/why-we-re-pausing-external-pull-requests) for the reasoning.

- Davinci Sign stays open source. You can read, audit, run, and fork the code.
- To report issues or propose changes, see our [contribution guide](https://github.com/documenso/documenso/blob/main/CONTRIBUTING.md).

## Contact us

Contact us if you are interested in our Enterprise plan for large organizations that need extra flexibility and control at [support@davincisolutions.ai](mailto:support@davincisolutions.ai).

## Tech Stack

<p align="left">
  <a href="https://www.typescriptlang.org"><img src="https://shields.io/badge/TypeScript-3178C6?logo=TypeScript&logoColor=FFF&style=flat-square" alt="TypeScript"></a>
  <a href="https://prisma.io"><img width="122" height="20" src="http://made-with.prisma.io/indigo.svg" alt="Made with Prisma" /></a>
  <a href="https://tailwindcss.com/"><img src="https://img.shields.io/badge/tailwindcss-0F172A?&logo=tailwindcss" alt="Tailwind CSS"></a>
</p>

- [TypeScript](https://www.typescriptlang.org/) - Language
- [React Router v7](https://reactrouter.com/) - Framework
- [Hono](https://hono.dev/) - Server
- [Prisma](https://www.prisma.io/) - ORM
- [Tailwind CSS](https://tailwindcss.com/) - CSS
- [shadcn/ui](https://ui.shadcn.com/) + [Radix UI](https://www.radix-ui.com/) - Component Library
- [react-email](https://react.email/) - Email Templates
- [Lingui](https://lingui.dev/) - Internationalization
- [tRPC](https://trpc.io/) - API
- [@documenso/pdf-sign](https://www.npmjs.com/package/@documenso/pdf-sign) - PDF Signatures
- [React-PDF](https://github.com/wojtekmaj/react-pdf) - Viewing PDFs
- [PDF-Lib](https://github.com/Hopding/pdf-lib) - PDF manipulation
- [Stripe](https://stripe.com/) - Payments

## Local Development

### Requirements

To run Davinci Sign locally, you will need

- Node.js (v22 or above)
- Postgres SQL Database
- Docker (optional)

### Developer Quickstart

> **Note**: This is a quickstart for developers. It assumes that you have both [docker](https://docs.docker.com/get-docker/) and [docker-compose](https://docs.docker.com/compose/) installed on your machine.

1. [Fork this repository](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/working-with-forks/about-forks) to your GitHub account.

After forking the repository, clone it to your local device by using the following command:

```sh
git clone https://github.com/<your-username>/documenso
```

2. Set up your `.env` file using the recommendations in the `.env.example` file. Alternatively, just run `cp .env.example .env` to get started with our handpicked defaults.

3. Run `npm run dx` in the root directory

4. Run `npm run dev` in the root directory

## Developer Setup

### Manual Setup

Follow these steps to setup Davinci Sign on your local machine, or refer to the [manual setup guide](https://docs.davincisolutions.ai/docs/developers/local-development/manual) for more details:

1. [Fork this repository](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/working-with-forks/about-forks) to your GitHub account.

After forking the repository, clone it to your local device by using the following command:

```sh
git clone https://github.com/<your-username>/documenso
```

2. Run `npm i` in the root directory

3. Create your `.env` from the `.env.example`. You can use `cp .env.example .env` to get started with our handpicked defaults.

4. Set the following environment variables:

   - NEXTAUTH_SECRET
   - NEXT_PUBLIC_WEBAPP_URL
   - NEXT_PRIVATE_DATABASE_URL
   - NEXT_PRIVATE_DIRECT_DATABASE_URL
   - NEXT_PRIVATE_SMTP_FROM_NAME
   - NEXT_PRIVATE_SMTP_FROM_ADDRESS

5. Create the database schema by running `npm run prisma:migrate-dev`

6. Run `npm run translate:compile` in the root directory to compile lingui

7. Run `npm run dev` in the root directory to start

8. Register a new user at http://localhost:3000/signup

---

- Optional: Seed the database using `npm run prisma:seed -w @documenso/prisma` to create a test user and document.
- Optional: Create your own signing certificate. See **[Create your own signing certificate](./SIGNING.md)**.

### Run in Gitpod

- Click below to launch a ready-to-use Gitpod workspace in your browser.

[![Open in Gitpod](https://gitpod.io/button/open-in-gitpod.svg)](https://gitpod.io/#https://github.com/documenso/documenso)

## Docker

Docker containers are available for running Davinci Sign. We support official Docker images for Davinci Sign on [DockerHub](https://hub.docker.com/r/davinci/davinci-sign) and [GitHub Container Registry](https://ghcr.io/davinci/davinci-sign).

For setup instructions, see the [Docker Deployment](https://docs.davincisolutions.ai/docs/self-hosting/deployment/docker) and [Docker Compose](https://docs.davincisolutions.ai/docs/self-hosting/deployment/docker-compose) guides.

### Support IPv6

If you are deploying to a cluster that uses only IPv6, you can use a custom command to pass a parameter to the Remix start command.

For local docker run:

```bash
docker run -it davinci/davinci-sign:latest npm run start -- -H ::
```
