from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    allowed_email_domains: str = ""
    google_client_id: str = ""
    google_client_secret: str = ""
    secret_key: str = ""

    model_config = SettingsConfigDict(env_file=".env")
