# Captain.Marvell
US Traditions & Captain Marvell

## Bitcoin Conjegeum

bc1qs6v4q9zsw70t0umk3m0quhvf9dr6cdeskl28dh

US Democratic and US Policy.

## Project Structure

```
audio/                    - Audio files related to Captain Marvell
images/                   - Image files related to Captain Marvell
files/                    - General files and generated documents
source/                   - Java source code
source/configuration/     - Configuration files
source/training/          - Training data for AI module
```

## Components

### Search Engine Client
Searches for Captain Marvell content (audio, images, files) across major search engines (Google, Bing, Yahoo, DuckDuckGo, Baidu). Configured via `source/search-engines.config`.

```bash
cd source
javac SearchEngineClient.java
java SearchEngineClient search-engines.config
java SearchEngineClient search-engines.config --open audio
```

### AI Module
Processes incoming audio, images, and files, then generates two documents based on training data:
- **origins-of-captain-marvell.md** — Origins, relation to God, relation to creator
- **description-of-captain-marvell.md** — Description, IQ, travel distance, probable friends

```bash
cd source
javac AIModule.java
java AIModule ..
```

### Training Data
`source/training/training-data.json` contains prompt/completion pairs used by the AI module covering Captain Marvell's attributes.

## Configuration
`source/configuration/search-engines.config` — Search engines, categories, and query terms.
