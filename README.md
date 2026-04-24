# Ohdieux

Converts RSS feeds for podcast clients.

Built with Scala 3, Play Framework, and Pekko typed actors. Optional media archival to local disk.

## Stack

- Scala 3 / Play Framework
- Pekko (typed actors) — scraping, downloads, stats
- Anorm + SQLite (JDBC) for persistence
- Twirl for RSS/XML rendering

## Architecture

```mermaid
flowchart LR
    Client([Podcast client])
    Admin([Admin UI])

    subgraph HTTP [Play HTTP layer]
        MC[ManifestController<br/>/rss]
        AC[AdminController<br/>/admin]
        MedC[MediaController<br/>/media]
    end

    MS[ManifestService]
    Twirl[[Twirl RSS template]]

    subgraph Actors [Pekko actor system]
        PSA(ProgrammeScraperActor)
        MSA(MediaScraperActor)
        FAA(FileArchiveActor)
        ASA(ArchiveStatisticsActor)
        PCA(ProgrammeConfigActor)
    end

    RC[(Upstream API)]
    DB[(SQLite)]
    FS[(Archive<br/>filesystem)]

    Client -->|GET /rss| MC
    Admin --> AC
    Client -->|GET /media| MedC

    MC --> MS --> Twirl
    MS --> DB
    MS -. auto-add on miss .-> PSA
    AC --> PSA
    AC --> MSA
    AC --> ASA
    AC --> PCA
    MedC --> FS
    MedC -->|proxy| RC

    PSA -- timer: refresh all --> PSA
    PSA -->|fetch programmes/episodes| RC
    PSA --> DB
    PSA -- NewEpisode --> MSA
    PSA -- SaveImage --> FAA

    MSA -->|resolve media URL| RC
    MSA --> DB
    MSA -- SaveMedia --> FAA

    FAA -->|download| RC
    FAA --> FS
    FAA -- Incr stats --> ASA
    ASA --> DB
    PCA --> DB
```

Flow: `ProgrammeScraperActor` polls the upstream API on a timer and hands each discovered episode to `MediaScraperActor`, which resolves the audio URL and (optionally) asks `FileArchiveActor` to download it. `ManifestController` reads the resulting data from SQLite and renders it as an RSS feed through a Twirl XML template. Media is always served through `MediaController` — archived files are served directly, otherwise the upstream URL is proxied to ensure podcast clients receive audio content (not upstream `.mp4` video containers).

