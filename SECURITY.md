# Sécurité

Rythmo est un MVP. Signaler les vulnérabilités via le
[signalement privé GitHub](https://github.com/cblgn/rythmo/security/advisories/new),
sans publier de données d'élèves, de code professeur ou de secret dans une issue.

Seule la version courante de `main` est maintenue. Les mises à jour sont vérifiées
par tests JVM, Android Lint, compilation, CodeQL, OSV et analyse de secrets ;
Dependabot suit les bibliothèques et GitHub Actions.

## Limites du MVP

Le serveur enseignant utilise HTTP sur un réseau local de confiance ou via le
tunnel USB. Les codes d'association et professeur ne chiffrent pas le transport.
Ne pas exposer ce serveur sur Internet. Les PDF et les sauvegardes de séances
contiennent des données personnelles : les conserver sur les appareils prévus
pour le cours, hors de Git et des artefacts CI.

Les protections du dépôt et leurs limites liées à l'offre GitHub sont décrites
dans [la configuration GitHub](docs/github-security.md). Une CI réussie ne
constitue pas un audit de sécurité de l'application.
