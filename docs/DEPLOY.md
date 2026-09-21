# Deploying ReconPilot for ~₹0 a year

The whole stack — Spring Boot, PostgreSQL, Kafka, Redis, nginx — needs roughly
2 GB of RAM to be comfortable. At the time of writing the cheapest paid VPS
that fits is about **₹3,800/year**, so the only way to run this for under ₹300
is a genuinely free tier.

| Piece | Choice | Cost |
|---|---|---|
| Server | Oracle Cloud **Always Free** ARM (Ampere A1) | ₹0 |
| DNS name | DuckDNS subdomain | ₹0 |
| Certificate | Let's Encrypt | ₹0 |
| CI | GitHub Actions, public repo | ₹0 |
| **Total** | | **₹0/year** |

An optional real domain (`.xyz`, `.site`) costs roughly ₹200 for the first year
and renews higher — worth it only if `reconpilot.duckdns.org` bothers you.

## Know the risks before you start

Oracle's Always Free tier is real and has no time limit, but it has two
well-known problems, and it is better to hear them now than after an evening
of clicking:

1. **The ARM allowance was halved in June 2026**, from 4 OCPU / 24 GB to
   **2 OCPU / 12 GB**, with no announcement. 12 GB is still roughly six times
   what this stack needs, so it does not affect us — but do not follow an older
   tutorial that tells you to ask for 4 OCPUs.
2. **Signup is aggressive about rejecting new accounts**, and ARM capacity is
   often exhausted in popular regions. Pick a large region (Mumbai or
   Hyderabad for India); if you see "Out of capacity", try again later or pick
   a different availability domain. This is the step most likely to cost you
   an evening.

If Oracle will not have you, there is no other free tier that fits this stack
— Google's and AWS's free VMs have 1 GB of RAM, which Kafka and the JVM will
not share. The honest options at that point are to pay about ₹3,800/year, or to
run the demo locally and show a recorded walkthrough instead.

---

## 1. The server

Create an **Always Free** VM: shape `VM.Standard.A1.Flex`, **2 OCPU, 12 GB**,
Ubuntu 24.04, and save the SSH key it gives you. Then:

```bash
ssh ubuntu@<public-ip>

sudo apt update && sudo apt install -y docker.io docker-compose-v2 git
sudo usermod -aG docker ubuntu
exit          # log out and back in, or the group change does not apply
```

### Open the ports — this is two separate firewalls

Oracle trips almost everyone here. There is a cloud-level firewall *and* a
firewall on the machine itself, and opening one does nothing without the other.

```bash
# 1. Cloud: in the OCI console, VCN -> Security Lists -> add Ingress Rules
#    allowing TCP 80 and TCP 443 from 0.0.0.0/0.

# 2. Host: Oracle's Ubuntu image ships with iptables already blocking them.
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80  -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

If `curl http://<public-ip>` times out later, it is almost always step 2.

> **Note on architecture.** The free tier is ARM64, and so is your Mac, so the
> images build identically in both places. We build on the server rather than
> pushing images from CI, which keeps it free — no container registry needed.

## 2. Get the code onto it

```bash
git clone https://github.com/<your-username>/reconpilot.git
cd reconpilot
cp .env.example .env
```

Fill in `.env` — generate the secrets, never invent them:

```bash
openssl rand -base64 48    # RECONPILOT_JWT_SECRET
openssl rand -base64 24    # DB_OWNER_PASSWORD, and again for DB_APP_PASSWORD
```

Set `DOMAIN` to the DuckDNS name from the next step, and leave
`TLS_MODE=selfsigned` for now.

## 3. The DNS name

Sign in at [duckdns.org](https://www.duckdns.org) with GitHub, create a
subdomain (say `reconpilot`), and point it at the server's public IP. Confirm
it before going further, because Let's Encrypt allows only **5 failed
validations per hostname per hour**:

```bash
dig +short reconpilot.duckdns.org     # must print the server's public IP
```

## 4. First boot, on a self-signed certificate

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.letsencrypt.yml up -d --build
```

The first build takes several minutes — Maven downloads the world. Then check
that the outside world can reach it:

```bash
curl -I http://reconpilot.duckdns.org      # expect 308, redirecting to https
```

## 5. The real certificate

```bash
# Rehearse against the staging CA first. It issues an untrusted certificate,
# but its rate limits are generous, so a DNS or firewall mistake costs nothing.
./ops/tls/issue-certificate.sh reconpilot.duckdns.org you@example.com --staging

# Once that succeeds, do it for real:
./ops/tls/issue-certificate.sh reconpilot.duckdns.org you@example.com
```

The script runs its own preflight — it plants a token and fetches it through
the public DNS name — so a firewall problem is reported as a firewall problem
rather than as a certbot stack trace.

Then switch on the real certificate:

```bash
sed -i 's/^TLS_MODE=.*/TLS_MODE=provided/' .env
docker compose -f docker-compose.prod.yml -f docker-compose.letsencrypt.yml up -d frontend

curl -sI https://reconpilot.duckdns.org | head -3
curl -sI https://reconpilot.duckdns.org | grep -i strict-transport-security
```

That second command is the proof it worked: HSTS is only sent when the
certificate is a real one (ADR 0015), so seeing it means nginx is no longer on
the self-signed one.

## 6. Keep it alive

```bash
# Renew daily. Certbot only acts in the last 30 days, so this is free on the
# other 60 -- and a certificate that expires because nobody noticed the cron
# had stopped is the most common way a small deployment dies, always exactly
# 90 days after everyone stopped thinking about it.
crontab -e
0 3 * * * cd ~/reconpilot && set -a && . ./.env && set +a && ./ops/tls/renew.sh >> ~/renew.log 2>&1

# Prove the backups restore. Monthly is plenty; the point is that it is run at
# all, because an untested backup is a rumour.
0 4 1 * * cd ~/reconpilot && docker compose -f docker-compose.prod.yml run --rm --entrypoint /ops/restore-drill.sh backup >> ~/drill.log 2>&1
```

## 7. Deploying a change

```bash
cd ~/reconpilot && git pull
docker compose -f docker-compose.prod.yml -f docker-compose.letsencrypt.yml up -d --build
```

There is no zero-downtime story here: the backend stops and starts, so there
is a gap of roughly forty seconds. For a portfolio deployment that is fine, and
pretending otherwise would be the more expensive lie.

## What this deployment is not

Everything in **D16** still applies, and an interviewer who asks is asking a
fair question. One Kafka broker, so broker loss is data loss. Backups on the
same disk as the database. No log aggregation, no alerting, no zero-downtime
deploys, and a single machine that is a single point of failure. It is a
correct small deployment, and knowing precisely why it is not a production one
is worth more in an interview than pretending it is.
