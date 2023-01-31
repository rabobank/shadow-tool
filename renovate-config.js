module.exports = {
    platform: 'github',
    endpoint: 'https://api.github.com/',
    token: process.env.TOKEN,
    hostRules: [
        {
            hostType: 'npm',
            matchHost: 'pkgs.dev.azure.com',
            username: 'apikey',
            password: process.env.TOKEN,
        },
    ],
    repositories: ['rabobank/shadow-tool'],
};

