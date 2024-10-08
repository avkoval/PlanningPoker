import asyncio
import copy
import json
import pprint
import re
from collections import defaultdict
from datetime import datetime
from typing import Any, List

import markdown
import starlette.status as status
from authlib.integrations.starlette_client import (  # type: ignore[import]
    OAuth, OAuthError)
from cachetools import TTLCache, cached
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from jira import JIRA
from jira.client import ResultList
from jira.exceptions import JIRAError
from jira.resources import Issue
from pydantic import BaseModel
from starlette.config import Config
from starlette.middleware.sessions import SessionMiddleware
from starlette.responses import RedirectResponse

# from .channels import get_next_message, publish
from .config import Settings
from .websocket import notifier

settings = Settings()

# OAuth settings
GOOGLE_CLIENT_ID = settings.google_client_id
GOOGLE_CLIENT_SECRET = settings.google_client_secret
if GOOGLE_CLIENT_ID is None or GOOGLE_CLIENT_SECRET is None:
    raise BaseException('Missing env variables')


# Set up oauth
config_data = {'GOOGLE_CLIENT_ID': GOOGLE_CLIENT_ID, 'GOOGLE_CLIENT_SECRET': GOOGLE_CLIENT_SECRET}
starlette_config = Config(environ=config_data)
oauth = OAuth(starlette_config)
oauth.register(
    name='google',
    server_metadata_url='https://accounts.google.com/.well-known/openid-configuration',
    client_kwargs={'scope': 'openid email profile'},
)

app = FastAPI()
app_data: dict = {
    "estimate-ticket": None,
    "votes": [],
    "users": [],
    "finished": False,
}


def get_estimate_ticket():
    global app_data
    return app_data['estimate-ticket']


def is_finished():
    global app_data
    return app_data['finished']


def store_reset(estimate_ticket: str | None) -> None:
    global app_data
    app_data['estimate-ticket'] = estimate_ticket
    app_data['votes'] = defaultdict(dict)
    app_data['users'] = []
    app_data['finished'] = False


class Vote(BaseModel):
    key: str = ""
    vote: str = ""
    category: str = ""
    stamp: datetime | None = None


def add_vote(username: str, vote: Vote):
    global app_data
    if app_data['estimate-ticket'] is None:
        store_reset(vote.key)
    if vote.key == app_data['estimate-ticket']:
        votes = app_data['votes'][username]
        votes.update({vote.category: vote.vote})
        app_data['votes'][username] = votes
    else:
        print(f"Error: vote ticket {vote.key} is not the same as global estimate ticket {app_data['estimate-ticket']}")
    pprint.pprint(app_data)


SECRET_KEY = settings.secret_key
if SECRET_KEY is None:
    raise Exception('Missing SECRET_KEY')
app.add_middleware(SessionMiddleware, secret_key=SECRET_KEY)

app.mount("/app/", StaticFiles(directory="pp-front/public", html=True), name="app")
app.mount("/static/", StaticFiles(directory="static", html=True), name="static")
app.mount("/js/", StaticFiles(directory="pp-front/public/js/", html=True), name="static")

favicon_path = 'static/favicon.ico'

jira = JIRA(
    options={
        "server": settings.jira_server
    },
    basic_auth=(settings.jira_user_email, settings.jira_token)
)


templates = Jinja2Templates(directory="templates")


@app.get("/", response_class=HTMLResponse)
async def home(request: Request):
    notifications: List = []
    if 'access_token' in request.session:
        domain = request.session['access_token']['userinfo']['email'].split('@')[1]
        allowed_domains = [d for d in settings.allowed_email_domains.split(',') if d != '']
        if len(allowed_domains) and domain not in allowed_domains:
            msg = f"Domain not allowed: {domain} for user {request.session['access_token']['userinfo']['email']}"
            notifications.append({'type': 'danger', 'text': msg})
        else:
            return RedirectResponse('/app/')
    return templates.TemplateResponse(
        name="index.html", context={'request': request, 'notifications': notifications}
    )


@app.get('/favicon.ico', include_in_schema=False)
async def favicon():
    return FileResponse(favicon_path)


@app.route('/login')
async def login(request: Request):
    redirect_uri = request.url_for('auth')  # This creates the url for the /auth endpoint
    return await oauth.google.authorize_redirect(request, redirect_uri)


@app.post('/logout')
async def logout(request: Request):
    if 'access_token' in request.session:
        del request.session['access_token']
    return RedirectResponse('/', status_code=status.HTTP_302_FOUND)


class UserInfo(BaseModel):
    logged_in: bool = False
    email: str | None = None
    family_name: str | None = None
    given_name: str | None = None
    name: str | None = None
    picture: str | None = None


@app.get('/userInfo')
async def user_info(request: Request) -> UserInfo:
    if not request.session.get('access_token'):
        return UserInfo()
    userinfo = request.session['access_token']['userinfo']
    return UserInfo(
        email=userinfo['email'],
        family_name=userinfo['family_name'],
        given_name=userinfo['given_name'],
        name=userinfo['name'],
        picture=userinfo['picture'],
    )


@app.route('/auth')
async def auth(request: Request):
    try:
        access_token = await oauth.google.authorize_access_token(request)
    except OAuthError as e:
        return templates.TemplateResponse(
            name="index.html", context={'request': request, 'error': e}
        )
    request.session['access_token'] = access_token
    return RedirectResponse('/app/')


class FoundIssue(BaseModel):
    key: str = ""
    url: str = ""
    summary: str = ""
    type: str = ""
    original_estimate: int | None = None


@cached(cache=TTLCache(maxsize=1024, ttl=600))
def search_jira_issues(search):
    try:
        return jira.search_issues(search)
    except JIRAError as e:
        print(search, ' => ', e.text)
    return []


@app.get('/jira/search')
def search(request: Request, q: str) -> List[FoundIssue]:
    if not request.session.get('access_token'):
        return []
    # user = username(request.session.get('access_token')['userinfo'])  # type: ignore

    issues: List | dict[str, Any] | ResultList[Issue] = []
    q = q.strip()
    if q.startswith('jql:'):
        search = q[4:]
    else:
        search = f'text ~ "{q}"'
        if '-' in q and q.index('-') == 2:
            search = f'key = "{q}"'
        if settings.limit_to_project:
            search += f' and project={settings.limit_to_project}'

    issues = search_jira_issues(search)
    return [
        FoundIssue(  # FIXME return type of `jira.search_issues` has some problem
            key=issue.key,  # type: ignore
            summary=issue.fields.summary,  # type: ignore
            url=issue.permalink(),  # type: ignore
            type=str(issue.fields.issuetype),  # type: ignore
            original_estimate=issue.fields.timeoriginalestimate,  # type: ignore
        )  # type: ignore
        for issue in issues
    ]


class IssueDetail(BaseModel):
    key: str = ""
    url: str = ""
    summary: str = ""
    description: str = ""
    updated: str = ""
    created: str = ""
    issuetype: str = ""
    priority: str = ""
    reporter: str = ""
    assignee: str = ""
    aggregatetimespent: int = 0
    comments: List[str] = []


@cached(cache=TTLCache(maxsize=1024, ttl=600))
def get_issue_info(issue_key: str):
    issue_key = issue_key.strip()
    try:
        return jira.issue(issue_key, expand='renderedFields')
    except JIRAError as e:
        print(issue_key, ' => ', e.text)
        return None


def remove_img_tags(data):
    p = re.compile(r'<img.*?/>')
    return p.sub('', data)


@app.get('/jira/info')
def detail(request: Request, issue_key: str) -> IssueDetail | None:
    if not request.session.get('access_token'):
        return None
    issue = get_issue_info(issue_key)
    user = get_username(request.session.get('access_token')['userinfo'])  # type: ignore
    now = format_datetime(datetime.now())
    if get_estimate_ticket() != issue.key:
        log_msg = f'{now} Voting started by {user} for Jira ticket № {issue.key}'
        print(log_msg)
        asyncio.run(push_to_connected_websockets(f"start voting:: {issue.key}"))
        asyncio.run(push_to_connected_websockets(f"log:: {log_msg}"))
        store_reset(issue.key)

    detail = IssueDetail(key=issue.key,
                         url=issue.permalink(),
                         summary=issue.fields.summary,
                         description=remove_img_tags(issue.raw['renderedFields']['description']),
                         updated=issue.fields.updated,
                         created=issue.fields.created,
                         issuetype=str(issue.fields.issuetype),
                         priority=str(issue.fields.priority),
                         reporter=str(issue.fields.reporter),
                         assignee=str(issue.fields.assignee),
                         aggregatetimespent=issue.fields.aggregatetimespent or 0,
                         )
    return detail


def get_username(userinfo: dict) -> str:
    return f"{userinfo['given_name']} {userinfo['family_name']} {userinfo['email']}"


def format_datetime(dt):
    return dt.strftime("%Y/%m/%d, %H:%M:%S")


@app.post('/vote')
def vote(request: Request, vote: Vote) -> Vote | None:
    if not request.session.get('access_token'):
        return None
    vote.stamp = datetime.now()
    username = get_username(request.session.get('access_token')['userinfo'])  # type: ignore
    if is_finished():
        votes = copy.deepcopy(app_data['votes'])
        msg = f"Invalid vote attempt from {username} as voting process is already finished"
        print(msg)
        asyncio.run(push_to_connected_websockets(f"log::{format_datetime(vote.stamp)} {msg}"))
        return None
    else:
        add_vote(username, vote)  # type: ignore
        votes = copy.deepcopy(app_data['votes'])

    asyncio.run(push_to_connected_websockets(f"log::{format_datetime(vote.stamp)} Got Vote from {username}"))
    for v in votes:
        for category in votes[v]:
            votes[v][category] = '✓'
    print(votes)
    asyncio.run(push_to_connected_websockets("results::" + json.dumps(votes)))
    return vote


class JiraEstimateComment(BaseModel):
    key: str = ""
    text: str = ""


def find_matching_comment(comments):
    """
    try to find comment, containing forms:
      Backend - .*
      QA - .*
      Back:
      Front:
      ... and qualify this comment as estimate, otherwise return None
    """
    for comment in comments:
        for pattern in [r'Backend\s\-.*', r'QA\s\-.*', r'Front\s\-.*', r'^Back:.*', r'^Front:.*']:
            if re.match(pattern, comment.body):
                return comment


@app.post('/add-estimate-comment')
def add_estimate_comment(request: Request, comment: JiraEstimateComment) -> JiraEstimateComment | None:
    if not request.session.get('access_token'):
        return None
    username = get_username(request.session.get('access_token')['userinfo'])  # type: ignore
    issue = jira.issue(comment.key)
    estimate_comment = find_matching_comment(issue.fields.comment.comments)
    if estimate_comment:
        estimate_comment.update(body=comment.text)
    else:
        jira.add_comment(issue, comment.text)
    asyncio.run(push_to_connected_websockets(
        f"log::{format_datetime(datetime.now())} Saved comment of {username} to ticket {comment.key}"))
    return comment


@app.post('/vote/finish')
def vote_finish(request: Request) -> None:
    global app_data
    if request.session.get('access_token'):
        app_data['finished'] = True
        asyncio.run(push_to_connected_websockets("results::" + json.dumps(app_data['votes'])))


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    global app_data
    await notifier.connect(websocket)
    try:
        while True:
            data = await websocket.receive_text()
            print('got some data over websocket:', data)
            match data:
                case 'sync':
                    current_ticket = get_estimate_ticket()
                    print('syncing new websocket client', )
                    if current_ticket:
                        print('current ticket is: ' + current_ticket)
                        await websocket.send_text(f"start voting:: {current_ticket}")
                        if is_finished():
                            await websocket.send_text("results::" + json.dumps(app_data['votes']))
    except WebSocketDisconnect:
        notifier.remove(websocket)
        print("WebSocketDisconnect detected")


@app.on_event("startup")
async def startup():
    await notifier.generator.asend(None)


async def push_to_connected_websockets(message: str):
    await notifier.push(message)
