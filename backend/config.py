import logging
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    log_level: str = "INFO"
    allowed_email_domains: str = ""
    google_client_id: str = ""
    google_client_secret: str = ""
    secret_key: str = ""
    jira_token: str = ""
    jira_user_email: str = ""
    jira_server: str = ""
    limit_to_project: str = ""

    jira_client_id: str = ""
    jira_client_secret: str = ""
    jira_cloud_id: str = ""  # Required for Jira OAuth API calls

    model_config = SettingsConfigDict(env_file=".env")
