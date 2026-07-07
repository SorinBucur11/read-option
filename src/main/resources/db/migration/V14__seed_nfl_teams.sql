-- V14: seed the 32 NFL teams (reference data — DDL/DML split is deliberate).
-- Sleeper abbrev is the PK; espn_abbrev diverges for exactly one team (WAS/WSH).
-- OAK is deliberately EXCLUDED: the Sleeper blob still carries active players with
-- the stale pre-relocation code — source noise, not a team. bye_week stays NULL
-- until the schedule sync derives it.

INSERT INTO nfl_team (abbrev, espn_abbrev, name) VALUES
    ('ARI', 'ARI', 'Arizona Cardinals'),
    ('ATL', 'ATL', 'Atlanta Falcons'),
    ('BAL', 'BAL', 'Baltimore Ravens'),
    ('BUF', 'BUF', 'Buffalo Bills'),
    ('CAR', 'CAR', 'Carolina Panthers'),
    ('CHI', 'CHI', 'Chicago Bears'),
    ('CIN', 'CIN', 'Cincinnati Bengals'),
    ('CLE', 'CLE', 'Cleveland Browns'),
    ('DAL', 'DAL', 'Dallas Cowboys'),
    ('DEN', 'DEN', 'Denver Broncos'),
    ('DET', 'DET', 'Detroit Lions'),
    ('GB',  'GB',  'Green Bay Packers'),
    ('HOU', 'HOU', 'Houston Texans'),
    ('IND', 'IND', 'Indianapolis Colts'),
    ('JAX', 'JAX', 'Jacksonville Jaguars'),
    ('KC',  'KC',  'Kansas City Chiefs'),
    ('LAC', 'LAC', 'Los Angeles Chargers'),
    ('LAR', 'LAR', 'Los Angeles Rams'),
    ('LV',  'LV',  'Las Vegas Raiders'),
    ('MIA', 'MIA', 'Miami Dolphins'),
    ('MIN', 'MIN', 'Minnesota Vikings'),
    ('NE',  'NE',  'New England Patriots'),
    ('NO',  'NO',  'New Orleans Saints'),
    ('NYG', 'NYG', 'New York Giants'),
    ('NYJ', 'NYJ', 'New York Jets'),
    ('PHI', 'PHI', 'Philadelphia Eagles'),
    ('PIT', 'PIT', 'Pittsburgh Steelers'),
    ('SEA', 'SEA', 'Seattle Seahawks'),
    ('SF',  'SF',  'San Francisco 49ers'),
    ('TB',  'TB',  'Tampa Bay Buccaneers'),
    ('TEN', 'TEN', 'Tennessee Titans'),
    ('WAS', 'WSH', 'Washington Commanders');
