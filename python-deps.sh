uv sync

cp ./.venv/lib/python3.13/site-packages/ecologits/data/electricity_mixes.csv ./src/main/resources/data/eg-elec.csv
cp ./.venv/lib/python3.13/site-packages/ecologits/data/models.json ./src/main/resources/data/eg-models.json

cp ./.venv/lib/python3.13/site-packages/litellm/model_prices_and_context_window_backup.json ./src/main/resources/data/ltllm-prices.json