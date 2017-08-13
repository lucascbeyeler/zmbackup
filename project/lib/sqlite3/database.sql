  create table backup_session(
    sessionID varchar primary key,
    initial_date timestamp not null,
    conclusion_date timestamp not null,
    size varchar not null,
    type varchar not null,
    status varchar not null
  );

  create table backup_account(
    id integer primary key autoincrement,
    accountID int not null,
    sessionID varchar not null,
    account_size varchar not null,
    email varchar not null,
    foreign key (sessionID) references backup_session(sessionID)
  );

  create table backup_queue(
    id integer primary key autoincrement,
    accountID int not null,
    sessionID varchar not null,
    foreign key (accountID) references backup_account(accountID),
    foreign key (sessionID) references backup_session(sessionID)
  );
