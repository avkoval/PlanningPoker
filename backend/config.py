from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    allowed_email_domains: str = ""
    google_client_id: str = ""
    google_client_secret: str = ""
    secret_key: str = ""
    jira_token: str = ""
    jira_user_email: str = ""
    jira_server: str = ""
    limit_to_project: str = ""

    model_config = SettingsConfigDict(env_file=".env")
